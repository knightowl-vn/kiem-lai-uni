package com.universe.screen.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.universe.screen", importOptions = {ImportOption.DoNotIncludeTests.class})
public class PublicScreenArchitectureTest {

    @ArchTest
    public static final ArchRule publicControllerMustNotDependOnPersistenceImplementationOrEntities =
            noClasses()
                    .that().haveSimpleName("PublicScreenController")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.infrastructure.persistence..",
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.hibernate.."
                    )
                    .because("Public Screen controller must be thin and depend exclusively on application use case, not on persistence details or JPA entities");

    @ArchTest
    public static final ArchRule publicControllerMustNotDependOnStorageAdaptersOrCloudSdks =
            noClasses()
                    .that().haveSimpleName("PublicScreenController")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.media.infrastructure.storage..",
                            "software.amazon.awssdk..",
                            "com.cloudinary..",
                            "com.backblaze.."
                    )
                    .because("Public Screen controller must never interact directly with cloud storage adapters or SDKs");

    @ArchTest
    public static final ArchRule publicCatalogUseCaseMustNotDependOnJpaOrPersistence =
            noClasses()
                    .that().haveSimpleName("GetPublicScreenCatalogUseCase")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.infrastructure..",
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.hibernate..",
                            "com.universe.media.."
                    )
                    .because("GetPublicScreenCatalogUseCase must remain decoupled from infrastructure, persistence, and foreign modules");

    @ArchTest
    public static final ArchRule publicCatalogDtoMustNotDependOnForeignModules =
            noClasses()
                    .that().haveSimpleName("PublicScreenCatalogItemDTO")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.media..",
                            "jakarta.persistence..",
                            "org.springframework.data.."
                    )
                    .because("PublicScreenCatalogItemDTO in application layer must remain pure and free of foreign module dependencies");

    @ArchTest
    public static final ArchRule publicTitleDetailUseCaseMustNotDependOnJpaOrPersistence =
            noClasses()
                    .that().haveSimpleName("GetPublicScreenTitleDetailUseCase")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.infrastructure..",
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.hibernate..",
                            "com.universe.media.."
                    )
                    .because("GetPublicScreenTitleDetailUseCase must remain decoupled from infrastructure, persistence, and foreign modules");

    @ArchTest
    public static final ArchRule publicTitleDetailDtoMustNotDependOnForeignModules =
            noClasses()
                    .that().haveSimpleNameStartingWith("PublicScreenTitleDetailDTO")
                    .or().haveSimpleNameStartingWith("PublicScreenEntryDTO")
                    .or().haveSimpleNameStartingWith("PublicScreenEntryDetailDTO")
                    .or().haveSimpleNameStartingWith("PublicScreenFranchise")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.media..",
                            "jakarta.persistence..",
                            "org.springframework.data.."
                    )
                    .because("Public Screen detail DTOs in application layer must remain pure and free of foreign module dependencies");

    @ArchTest
    public static final ArchRule publicEntryDetailUseCaseMustNotDependOnJpaOrPersistence =
            noClasses()
                    .that().haveSimpleName("GetPublicScreenEntryDetailUseCase")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.infrastructure..",
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.hibernate..",
                            "com.universe.media.."
                    )
                    .because("GetPublicScreenEntryDetailUseCase must remain decoupled from infrastructure, persistence, and foreign modules");

    @ArchTest
    public static final ArchRule publicExceptionHandlerMustNotDependOnPersistenceOrStorage =
            noClasses()
                    .that().haveSimpleName("PublicScreenExceptionHandler")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.universe.screen.infrastructure.persistence..",
                            "com.universe.media.infrastructure.storage..",
                            "jakarta.persistence..",
                            "org.springframework.data.."
                    )
                    .because("PublicScreenExceptionHandler must remain presentation-only and free of persistence or storage dependencies");
}
