pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "proactive-context-agent-companion"
include(":phone", ":glasses")
include(":core:model")
include(":core:hud")
include(":core:ui")
include(":services:ai")
include(":services:memory")
