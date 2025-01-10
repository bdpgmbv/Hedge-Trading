package dev.hedge.ingestion;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.hedge.ingestion", importOptions = ImportOption.DoNotIncludeTests.class)
class IngestionBoundaryTest {

    @ArchTest
    static final ArchRule ingestion_does_not_know_about_reconciliation = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.hedge.reconciliation..")
            .because("ingestion records what providers said; comparing those statements is a"
                    + " separate job, and a dependency in this direction would let parsing"
                    + " decisions leak into reconciliation decisions");

    @ArchTest
    static final ArchRule provider_adapters_never_reach_the_database = noClasses()
            .that()
            .resideInAPackage("dev.hedge.ingestion.provider..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "dev.hedge.ingestion.raw..")
            .because("an adapter turns bytes into records and nothing else; one that could also"
                    + " write to the database would be impossible to test without a database and"
                    + " would make the order of parsing and storing an implementation detail"
                    + " rather than a property of the pipeline");

    @ArchTest
    static final ArchRule the_source_contract_stays_free_of_parsing_libraries = noClasses()
            .that()
            .resideInAPackage("dev.hedge.ingestion.source..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.apache.commons.csv..", "com.fasterxml.jackson..")
            .because("the interface every transport implements must not presuppose a format;"
                    + " a Kafka source has no CSV in it");

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
            .because("quantities and prices arrive here as text and must reach the domain as exact"
                    + " decimals; a double anywhere on that path loses cents before any"
                    + " calculation has happened");
}
