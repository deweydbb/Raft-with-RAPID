**This project will ***not*** be proactively monitored/updated and is ***not*** production ready**

# Raft with RAPID
Raft with RAPID consensus implementation in java. Relies on slightly modified version of [RAPID](https://github.com/deweydbb/rapid):

## Code Structure
This project contains not that much code, as it's well abstracted, here is the project structure
* **core**, the core Raft with RAPID algorithm implementation
* **exts** provides
  * TCP based CompletableFuture<T> enabled RPC client and server
  * FileBasedSequentialLogStore
  * A Log4j based Logger
* **kvstore**, a very basic key value store state machine and application used for testing
* **setup**, some scripts for Linux/Windows platform to run kvstore locally. USed development
* **experiments**, scripts for running clusters on ec2 instances and testing scripts

## Run kvstore
**kvstore** is a very basic key value store. A set of setup scripts is in the **setup** folder.
1. Export three projects into one executable jar file, called kvstore.jar
2. Start a command prompt, change directory to **setup** folder
3. Run **setup.sh**, it will start three instances of Raft with RAPID and a client
4. Enter client commands:
   1. `put:key:value`
   2. `get:key`

## Compiling the Code
* Open your Maven `settings.xml` typically located at `~/.m2/settings.xml`
* Add the following code in the `<repositories>` tag:
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/deweydbb/rapid</url>
  <snapshots>
    <enabled>true</enabled>
  </snapshots>
</repository>
```
* Run `mvn package`
* The jar file will be in the `target` folder in `kvstore` named `kvstore-1.0.0-jar-with-dependencies.jar`