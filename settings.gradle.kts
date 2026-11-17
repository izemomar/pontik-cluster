// SPDX-License-Identifier: MPL-2.0

/*
|--------------------------------------------------------------------------
| Repositories
|--------------------------------------------------------------------------
|
| Every library the build resolves comes from Maven Central, and a project
| that declares a repository of its own fails the build. Build plugins that
| do not ship with Gradle come from the Gradle Plugin Portal, which Gradle
| uses whenever the settings name no plugin repository, so we name none.
|
*/

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pontik-cluster"

include("plugin")
