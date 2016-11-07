# Java Service for the Monto Broker

## Requirements

 * Java 1.8
 * [services-base-java](https://github.com/monto-editor/services-base-java). Clone the `services-base-java` repository into the same folder as this one.

## Building
Builds and dependencies are managed with Gradle.

Run `./gradlew` to build the project. The build produces a jar containing all dependencies: `build/libs/services-java-all.jar`.

## Running
Start services with `./start.sh`.

## Developing
Setup your favorite IDE, then run or debug the [Main.java](src/main/java/monto/service/java8/Main.java) class and set the CLI arguments to the ones used in the `start.sh` script.

### IntelliJ setup
After cloning, use the `Import Project` or `File -> New -> Project from Existing Sources...` feature and select the `build.gradle` to start the import.

For any subsequent Monto language services, use `File -> New -> Module from Existing Sources...`.

### Eclipse setup
Make sure you have a up-to-date Buildship Gradle Plug-in installed. At the time of writing Eclipse 4.5.2 (Mars 2) is the newest stable Eclipse build. It ships with the Buildship Gradle Plug-in version 1.0.8, but you will need at least 1.0.10, because of [these changes](https://discuss.gradle.org/t/gradle-prefs-contains-absolute-paths/11475/34). To update Buildship, use the Eclipse Marketplace's `Installed` tab.

After cloning, use the `File -> Import -> Existing Projects into Workspace` feature and select the cloned folder.