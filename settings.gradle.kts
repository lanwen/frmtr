pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "frmtr"

include("frmtr-core")
include("frmtr-tooling")
include("frmtr-cli")
include("frmtr-gradle-plugin")
include("frmtr-native-image-support")
include("site")
