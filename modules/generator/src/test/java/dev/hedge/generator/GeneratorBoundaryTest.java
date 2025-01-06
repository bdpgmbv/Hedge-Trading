package dev.hedge.generator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
@AnalyzeClasses(packages = "dev.hedge.generator", importOptions = ImportOption.DoNotIncludeTests.class)
class GeneratorBoundaryTest {

    @ArchTest
    static final ArchRule the_generator_uses_only_the_domain_and_the_jdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("java..", "javax..", "dev.hedge.domain..", "dev.hedge.generator..")
            .because("the generator produces test data; if it pulls in a framework, every benchmark and"
                    + " test that uses it inherits that dependency");
}