package dev.hedge.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
@AnalyzeClasses(packages = "dev.hedge.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainBoundaryTest {

    @ArchTest
    static final ArchRule the_domain_depends_only_on_the_jdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("java..", "javax..", "dev.hedge.domain..")
            .because("the domain carries the financial meaning of the system; if it can reach a framework,"
                    + " a web annotation or a database type, that meaning becomes impossible to test in"
                    + " isolation and impossible to reuse from another layer");

    @ArchTest
    static final ArchRule no_money_is_held_in_floating_point = fields()
            .should()
            .notHaveRawType(double.class)
            .andShould()
            .notHaveRawType(float.class)
            .andShould()
            .notHaveRawType(Double.class)
            .andShould()
            .notHaveRawType(Float.class)
            .because("floating point cannot represent decimal amounts exactly, so a financial value stored"
                    + " this way is wrong before any calculation has even happened");
}