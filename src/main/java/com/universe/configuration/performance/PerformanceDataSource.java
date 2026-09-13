package com.universe.configuration.performance;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

final class PerformanceDataSource implements DataSource {

    private final DataSource delegate;
    private final LongSupplier nanoTime;

    PerformanceDataSource(DataSource delegate) {
        this(delegate, System::nanoTime);
    }

    PerformanceDataSource(DataSource delegate, LongSupplier nanoTime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return acquireConnection(delegate::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return acquireConnection(() -> delegate.getConnection(username, password));
    }

    private Connection acquireConnection(SqlConnectionSupplier supplier) throws SQLException {
        long started = nanoTime.getAsLong();
        Connection connection;
        try {
            connection = supplier.get();
        } finally {
            PerformanceRequestMetrics metrics = PerformanceRequestContext.currentOrNull();
            if (metrics != null) {
                metrics.recordConnectionAcquisition(nanoTime.getAsLong() - started);
            }
        }
        return JdbcProxy.connection(connection, nanoTime);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    @FunctionalInterface
    private interface SqlConnectionSupplier {
        Connection get() throws SQLException;
    }

    private static final class JdbcProxy implements InvocationHandler {

        private static final Set<String> STATEMENT_EXECUTION_METHODS = Set.of(
                "execute",
                "executeQuery",
                "executeUpdate",
                "executeLargeUpdate",
                "executeBatch",
                "executeLargeBatch"
        );

        private static final Set<String> TRANSACTION_CONTROL_METHODS = Set.of(
                "setAutoCommit",
                "commit",
                "rollback",
                "setReadOnly",
                "setTransactionIsolation"
        );

        private final Object delegate;
        private final LongSupplier nanoTime;
        private final ProxyKind kind;

        private JdbcProxy(Object delegate, LongSupplier nanoTime, ProxyKind kind) {
            this.delegate = delegate;
            this.nanoTime = nanoTime;
            this.kind = kind;
        }

        static Connection connection(Connection delegate, LongSupplier nanoTime) {
            return (Connection) Proxy.newProxyInstance(
                    PerformanceDataSource.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    new JdbcProxy(delegate, nanoTime, ProxyKind.CONNECTION)
            );
        }

        private static Statement statement(Statement delegate, LongSupplier nanoTime) {
            Class<?> statementInterface;
            if (delegate instanceof CallableStatement) {
                statementInterface = CallableStatement.class;
            } else if (delegate instanceof PreparedStatement) {
                statementInterface = PreparedStatement.class;
            } else {
                statementInterface = Statement.class;
            }
            return (Statement) Proxy.newProxyInstance(
                    PerformanceDataSource.class.getClassLoader(),
                    new Class<?>[]{statementInterface},
                    new JdbcProxy(delegate, nanoTime, ProxyKind.STATEMENT)
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, method, arguments);
            }
            if ("unwrap".equals(method.getName()) && arguments != null && arguments.length == 1) {
                Class<?> requestedType = (Class<?>) arguments[0];
                if (requestedType.isInstance(proxy)) {
                    return proxy;
                }
            }
            if ("isWrapperFor".equals(method.getName()) && arguments != null && arguments.length == 1) {
                Class<?> requestedType = (Class<?>) arguments[0];
                if (requestedType.isInstance(proxy)) {
                    return true;
                }
            }
            if (kind == ProxyKind.CONNECTION
                    && TRANSACTION_CONTROL_METHODS.contains(method.getName())) {
                return invokeTimed(method, arguments, TimingKind.TRANSACTION_CONTROL);
            }
            if (kind == ProxyKind.STATEMENT
                    && STATEMENT_EXECUTION_METHODS.contains(method.getName())) {
                return invokeTimed(method, arguments, TimingKind.SQL_EXECUTION);
            }

            Object result = invokeDelegate(method, arguments);
            if (kind == ProxyKind.CONNECTION && result instanceof Statement statement) {
                return statement(statement, nanoTime);
            }
            return result;
        }

        private Object invokeTimed(Method method, Object[] arguments, TimingKind timingKind) throws Throwable {
            long started = nanoTime.getAsLong();
            try {
                return invokeDelegate(method, arguments);
            } finally {
                PerformanceRequestMetrics metrics = PerformanceRequestContext.currentOrNull();
                if (metrics != null) {
                    long duration = nanoTime.getAsLong() - started;
                    if (timingKind == TimingKind.SQL_EXECUTION) {
                        metrics.recordSqlExecution(duration);
                    } else {
                        metrics.recordTransactionControl(duration);
                    }
                }
            }
        }

        private Object invokeDelegate(Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private Object invokeObjectMethod(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Performance-instrumented JDBC " + kind.name().toLowerCase();
                default -> throw new IllegalStateException("Unsupported Object method: " + method.getName());
            };
        }

        private enum ProxyKind {
            CONNECTION,
            STATEMENT
        }

        private enum TimingKind {
            SQL_EXECUTION,
            TRANSACTION_CONTROL
        }
    }
}
