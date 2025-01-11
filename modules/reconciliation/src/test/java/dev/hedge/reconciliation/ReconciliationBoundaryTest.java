package dev.hedge.reconciliation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.hedge.reconciliation", importOptions = ImportOption.DoNotIncludeTests.class)
class ReconciliationBoundaryTest {

    @ArchTest
    static final ArchRule reconciliation_never_learns_how_data_arrived = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.hedge.ingestion..", "org.apache.commons.csv..",
                    "com.fasterxml.jackson..", "java.nio.file..")
            .because("comparing two statements must not depend on whether either arrived as CSV, as"
                    + " JSON, over SFTP or from a stream; an engine that knew would have to be"
                    + " rewritten to reconcile a Kafka feed against a file");

    @ArchTest
    static final ArchRule reconciliation_reads_nothing_of_its_own = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..")
            .because("the engine takes both sides as arguments, which is what makes a twenty"
                    + " thousand position comparison testable without a database");

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
            .because("a break is a difference between two amounts, and a difference computed in"
                    + " floating point would invent breaks that are not there");
}
