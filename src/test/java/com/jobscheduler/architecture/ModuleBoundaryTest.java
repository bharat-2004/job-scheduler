package com.jobscheduler.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Package-by-module: api, scheduler, worker and notification may import only common, never each other,
 * so any of them could be split into its own service by moving files. The build fails otherwise.
 */
@AnalyzeClasses(packages = "com.jobscheduler", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule modulesDoNotDependOnEachOther = slices()
            .matching("com.jobscheduler.(api|scheduler|worker|notification)..")
            .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule commonDependsOnNoModule = noClasses()
            .that().resideInAPackage("com.jobscheduler.common..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.jobscheduler.api..", "com.jobscheduler.scheduler..",
                    "com.jobscheduler.worker..", "com.jobscheduler.notification..");

    @ArchTest
    static final ArchRule domainIsFreeOfInfrastructure = noClasses()
            .that().resideInAPackage("com.jobscheduler.common.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.amqp..", "io.minio..", "com.github.dockerjava..",
                    "org.springframework.web..", "org.springframework.data..");
}
