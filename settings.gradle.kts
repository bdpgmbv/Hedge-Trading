pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "hedge-trading"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(":modules:domain")
include(":modules:generator")
include(":modules:calculation")
include(":modules:ingestion")
include(":modules:reconciliation")
include(":modules:persistence")
include(":applications:hedge-api")
include(":applications:hedge-ingestor")