package dev.hedge.calculation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
@AnalyzeClasses(packages = "dev.hedge.calculation", importOptions = ImportOption.DoNotIncludeTests.class)
class CalculationBoundaryTest {

    @ArchTest
    static final ArchRule the_calculator_uses_only_the_domain_and_the_jdk = noClasses()
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages(
                    "java..", "javax..", "dev.hedge.domain..", "dev.hedge.calculation..")
            .because("the calculator is the oracle every later optimisation is measured against;"
                    + " it must be callable from a plain benchmark with no framework or database"
                    + " present, so it takes its data as arguments and fetches nothing");

    @ArchTest
    static final ArchRule the_calculator_never_reaches_for_data = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "dev.hedge.persistence..")
            .because("a calculation that can issue a query cannot be benchmarked or replayed,"
                    + " because its cost and its answer would depend on a database");

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
            .because("the same rule the domain enforces must hold here, or exact amounts would be"
                    + " destroyed the moment they entered a calculation");
}