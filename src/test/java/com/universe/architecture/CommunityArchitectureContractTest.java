package com.universe.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture contract test suite protecting MS-07 Community module boundaries.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Community Clean Architecture dependency direction (infrastructure -&gt; application -&gt; domain)</li>
 *   <li>Community domain purity (innermost, pure Java, framework-free, zero outer layers/Spring/JPA/servlet/foreign dependencies)</li>
 *   <li>Community application isolation (depends only inward on domain, no outer layers/persistence/web/foreign dependencies)</li>
 *   <li>Absence of cross-context persistence coupling across all Community packages</li>
 *   <li>Protection of Community internals from foreign bounded contexts</li>
 *   <li>Community entry web controllers must not depend directly on infrastructure persistence</li>
 *   <li>Community contracts DTOs must remain pure data contracts free of internal domain/application/persistence packages</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.universe", importOptions = {ImportOption.DoNotIncludeTests.class})
public class CommunityArchitectureContractTest {

    @ArchTest
    public static final ArchRule communityDomainMustRemainInnermostPureAndFrameworkFree =
            noClasses()
                    .that().resideInAPackage("com.universe.community.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.community.application..",
                            "com.universe.community.infrastructure..",
                            "com.universe.community.entry..",
                            "com.universe.identity..",
                            "com.universe.interaction..",
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "jakarta.servlet..",
                            "org.springframework.."
                    )
                    .because("Community domain must remain innermost, pure Java, framework-free, and isolated from outer layers and foreign bounded contexts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule communityApplicationMustNotDependOnOuterLayersPersistenceWebOrForeignModules =
            noClasses()
                    .that().resideInAPackage("com.universe.community.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.community.infrastructure..",
                            "com.universe.community.entry..",
                            "com.universe.identity..",
                            "com.universe.interaction..",
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "jakarta.persistence..",
                            "org.hibernate..",
                            "org.springframework.data..",
                            "jakarta.servlet..",
                            "org.springframework.web.."
                    )
                    .because("Community application layer must depend only inward on domain, use local ports, and remain free of outer layers, persistence, servlet/web APIs, and direct foreign module dependencies")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule communityMustNeverDependOnForeignPersistenceInternals =
            noClasses()
                    .that().resideInAPackage("com.universe.community..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.identity.infrastructure.persistence..",
                            "com.universe.interaction.infrastructure.persistence..",
                            "com.universe.novel.infrastructure.persistence..",
                            "com.universe.wiki.infrastructure.persistence.."
                    )
                    .because("Community must never access internal persistence implementations of other bounded contexts")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule foreignModulesMustNotDependOnCommunityInternals =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.universe.novel..",
                            "com.universe.wiki..",
                            "com.universe.identity..",
                            "com.universe.interaction..",
                            "com.universe.media..",
                            "com.universe.admin.."
                    )
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.community.domain..",
                            "com.universe.community.application..",
                            "com.universe.community.infrastructure.."
                    )
                    .because("External bounded contexts must not depend on Community internal implementation packages")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule communityEntryMustNotDependOnInfrastructurePersistence =
            noClasses()
                    .that().resideInAPackage("com.universe.community.entry..")
                    .should().dependOnClassesThat().resideInAPackage("com.universe.community.infrastructure.persistence..")
                    .because("Community web controllers must not couple directly to infrastructure persistence mappers or entities")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule communityContractsMustNotDependOnInternalPackages =
            noClasses()
                    .that().resideInAPackage("com.universe.community.contracts..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.community.domain..",
                            "com.universe.community.application..",
                            "com.universe.community.infrastructure..",
                            "com.universe.community.entry.."
                    )
                    .because("Community contracts must remain pure data contracts and not depend on internal domain, application, persistence, or entry packages")
                    .allowEmptyShould(true);

    @ArchTest
    public static final ArchRule communityInfrastructureIdentityMustOnlyDependOnIdentityContracts =
            noClasses()
                    .that().resideInAPackage("com.universe.community.infrastructure.identity..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.identity.application..",
                            "com.universe.identity.domain..",
                            "com.universe.identity.infrastructure.."
                    )
                    .because("Community infrastructure identity adapter must interact with Identity context exclusively via identity.contracts.*")
                    .allowEmptyShould(true);
}
