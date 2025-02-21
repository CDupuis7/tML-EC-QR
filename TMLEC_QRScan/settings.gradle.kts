pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    //Just in case the versions are not pulled automatically
//    versionCatalogs {
//        create("libs") { // Initialize the `libs` alias for Version Catalogs
//            from(files("gradle/libs.versions.toml"))
//        }
//    }
}


rootProject.name = "TML-EC_QR-Scan"
include(":app")
include(":opencv")
