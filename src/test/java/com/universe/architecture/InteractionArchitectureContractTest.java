package com.universe.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture contract test suite protecting MS-05A Interaction module boundaries.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Interaction Clean Architecture dependency direction (entry/infrastructure -&gt; application -&gt; domain)</li>
 *   <li>Interaction domain purity (innermost, pure Java, framework-free, zero outer layers/Spring/JPA/servlet/foreign dependencies)</li>
 *   <li>Interaction application isolation (depends only inward on domain, no outer layers/persistence/web/foreign dependencies)</li>
 *   <li>Strict Identity infrastructure entry allowlist (only AuthenticatedRequestIdentityAccessor allowed in entry)</li>
 *   <li>Lower layers strictly forbidden from Identity infrastructure</li>
 *   <li>Absence of cross-context persistence coupling across all Interaction packages</li>
 *   <li>Protection of Interaction internals from foreign bounded contexts</li>
 * </ul>
 *
 * <p>All rules configure {@code allowEmptyShould(true)} so they evaluate cleanly today before
 * production classes are added in future feature milestones (MS-05D, MS-05E, MS-05I).
 */
@AnalyzeClasses(packages = "com.universe", importOptions = {ImportOption.DoNotIncludeTests.class})
public class InteractionArchitectureContractTest {

    private static final String AUTHENTICATED_REQUEST_IDENTITY_ACCESSOR =
            "com.universe.identity.infrastructure.security.AuthenticatedRequestIdentityAccessor";

    private static final DescribedPredicate<JavaClass> IDENTITY_INFRASTRUCTURE_EXCEPT_AUTHENTICATED_ACCESSOR =
            new DescribedPredicate<>("classes in com.universe.identity.infrastructure.. other than AuthenticatedRequestIdentityAccessor") {
                @Override
                public boolean test(JavaClass javaClass) {
                    boolean isInIdentityInfrastructure =
                            javaClass.getPackageName().equals("com.universe.identity.infrastructure")
                                    || javaClass.getPackageName().startsWith("com.universe.identity.infrastructure.");
                    boolean isAllowedAccessor =
                            javaClass.getName().equals(AUTHENTICATED_REQUEST_IDENTITY_ACCESSOR)
                                    || javaClass.getName().startsWith(AUTHENTICATED_REQUEST_IDENTITY_ACCESSOR + "$");
                    return isInIdentityInfrastructure && !isAllowedAccessor;
                }
            };

    @ArchTest
    public static final ArchRule domainMustRemainInnermostPureAndFrameworkFree =
            noClasses()
                    .that().resideInAPackage("com.universe.interaction.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.interaction.application..",
                            "com.universe.interaction.infrastructure..",
                            "com.universe.interaction.entry..",
                            "com.universe.identity..",
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "jakarta.servlet..",
                            "org.springframework.."
                    )
                    .because("Interaction domain must remain innermost, pure Java, framework-free, and isolated from outer layers and foreign bounded contexts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule applicationMustNotDependOnOuterLayersPersistenceWebOrForeignModules =
            noClasses()
                    .that().resideInAPackage("com.universe.interaction.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.interaction.infrastructure..",
                            "com.universe.interaction.entry..",
                            "com.universe.identity..",
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "org.springframework.data..",
                            "jakarta.servlet..",
                            "org.springframework.web.."
                    )
                    .because("Interaction application layer must depend only inward on domain, use local ports, and remain free of outer layers, persistence, servlet/web APIs, and direct foreign module dependencies")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule entryMayOnlyDependOnAuthenticatedAccessorFromIdentityInfrastructure =
            noClasses()
                    .that().resideInAPackage("com.universe.interaction.entry..")
                    .should().dependOnClassesThat(IDENTITY_INFRASTRUCTURE_EXCEPT_AUTHENTICATED_ACCESSOR)
                    .because("Interaction entry may only access AuthenticatedRequestIdentityAccessor from identity infrastructure and no other identity infrastructure classes")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule lowerLayersMustNotDependOnIdentityInfrastructure =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.universe.interaction.domain..",
                            "com.universe.interaction.application..",
                            "com.universe.interaction.infrastructure.."
                    )
                    .should().dependOnClassesThat().resideInAnyPackage("com.universe.identity.infrastructure..")
                    .because("Interaction domain, application, and infrastructure layers must not depend on any identity infrastructure classes (including AuthenticatedRequestIdentityAccessor)")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule interactionMustNeverDependOnForeignPersistenceInternals =
            noClasses()
                    .that().resideInAPackage("com.universe.interaction..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.identity.infrastructure.persistence..",
                            "com.universe.novel.infrastructure.persistence..",
                            "com.universe.wiki.infrastructure.persistence.."
                    )
                    .because("Interaction must never access internal persistence implementations of other bounded contexts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule foreignModulesMustNotDependOnInteractionInternals =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "com.universe.identity..",
                            "com.universe.media..",
                            "com.universe.admin.."
                    )
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.interaction.domain..",
                            "com.universe.interaction.application..",
                            "com.universe.interaction.infrastructure.."
                    )
                    .because("External bounded contexts must not depend on Interaction internal implementation packages")
                    .allowEmptyShould(true);
}
