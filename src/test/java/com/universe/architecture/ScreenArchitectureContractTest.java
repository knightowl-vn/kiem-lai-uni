package com.universe.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture contract test suite protecting MS-08 Screen module boundaries.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Screen domain purity (innermost, pure Java, framework-free, zero outer layers/Spring/JPA/servlet/foreign dependencies)</li>
 *   <li>Screen application isolation (depends only inward on domain, no outer layers/persistence/web/foreign dependencies)</li>
 *   <li>Screen domain has zero direct dependency on Media domain/infrastructure (Media references remain scalar UUIDs)</li>
 *   <li>Absence of cross-context persistence coupling across all Screen packages</li>
 *   <li>Protection of Screen internals from foreign bounded contexts</li>
 * </ul>
 *
 * <p>All rules configure {@code allowEmptyShould(true)} to evaluate cleanly across current and future milestones.
 */
@AnalyzeClasses(packages = "com.universe", importOptions = {ImportOption.DoNotIncludeTests.class})
public class ScreenArchitectureContractTest {

    @ArchTest
    public static final ArchRule screenDomainMustRemainInnermostPureAndFrameworkFree =
            noClasses()
                    .that().resideInAPackage("com.universe.screen.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.application..",
                            "com.universe.screen.infrastructure..",
                            "com.universe.screen.entry..",
                            "com.universe.identity..",
                            "com.universe.interaction..",
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "com.universe.community..",
                            "com.universe.media..",
                            "com.universe.search..",
                            "com.universe.admin..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "jakarta.servlet..",
                            "org.thymeleaf..",
                            "org.springframework.."
                    )
                    .because("Screen domain must remain innermost, pure Java, framework-free, and isolated from outer layers, web/template engines, and foreign bounded contexts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule screenDomainMustNotDependOnMedia =
            noClasses()
                    .that().resideInAPackage("com.universe.screen.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.universe.media..")
                    .because("Screen domain must reference Media assets exclusively via scalar UUIDs and have no direct dependency on Media domain or infrastructure")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule screenInfrastructureMustNotDependOnMedia =
            noClasses()
                    .that().resideInAPackage("com.universe.screen.infrastructure..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.universe.media..")
                    .because("Screen persistence infrastructure must reference Media assets exclusively via scalar UUIDs and have no direct dependency on Media entities, repositories, or contracts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule screenApplicationMustNotDependOnOuterLayersPersistenceWebOrForeignModules =
            noClasses()
                    .that().resideInAPackage("com.universe.screen.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.infrastructure..",
                            "com.universe.screen.entry..",
                            "com.universe.identity..",
                            "com.universe.interaction..",
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "com.universe.community..",
                            "com.universe.media..",
                            "com.universe.search..",
                            "com.universe.admin..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "org.springframework.data..",
                            "jakarta.servlet..",
                            "org.thymeleaf..",
                            "org.springframework.web.."
                    )
                    .because("Screen application layer must depend only inward on domain, use local ports, and remain free of outer layers, persistence, servlet/web APIs, template engines, and direct foreign module dependencies")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule screenMustNeverDependOnForeignPersistenceInternals =
            noClasses()
                    .that().resideInAPackage("com.universe.screen..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.identity.infrastructure.persistence..",
                            "com.universe.interaction.infrastructure.persistence..",
                            "com.universe.novel.infrastructure.persistence..",
                            "com.universe.wiki.infrastructure.persistence..",
                            "com.universe.community.infrastructure.persistence..",
                            "com.universe.media.infrastructure.persistence..",
                            "com.universe.search.infrastructure.persistence.."
                    )
                    .because("Screen must never access internal persistence implementations of other bounded contexts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule foreignModulesMustNotDependOnScreenInternals =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "com.universe.identity..",
                            "com.universe.interaction..",
                            "com.universe.community..",
                            "com.universe.media..",
                            "com.universe.search..",
                            "com.universe.admin.."
                    )
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.domain..",
                            "com.universe.screen.application..",
                            "com.universe.screen.infrastructure.."
                    )
                    .because("External bounded contexts must not depend on Screen internal domain, application, or infrastructure packages")
                    .allowEmptyShould(true);
}
