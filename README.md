# Embedded Tomcat Starters

This repository packages the existing embedded Tomcat launcher as three release-ready Maven modules:

- `tomcat9` for Java EE 8 / `javax.*` applications on Tomcat `9.0.118`
- `tomcat10` for Jakarta EE 10 / `jakarta.*` applications on Tomcat `10.1.55`
- `tomcat11` for Jakarta EE 11 / `jakarta.*` applications on Tomcat `11.0.22`

All three modules compile the same shared launcher sources from `shared-java` and only vary the Tomcat dependency line. That keeps the implementation generalized while still producing one runnable jar per Tomcat major.

## Build

From the repository root:

Use a JDK 25 toolchain before building.

```sh
./mvnw clean package
```

This produces one fat jar per module:

- `tomcat9/target/embedded-tomcat-starter-tomcat9-x.jar`
- `tomcat10/target/embedded-tomcat-starter-tomcat10-x.jar`
- `tomcat11/target/embedded-tomcat-starter-tomcat11-x.jar`

## Run

Each jar supports the same launcher arguments:

```sh
java -jar tomcat9/target/embedded-tomcat-starter-tomcat9-x.jar --appProject=C:\w\ws\backend --contextXml=C:\w\java\backend\conf\Catalina\localhost\backend.xml --contextPath=/backend --port=8080
```

`--appProject` and `--contextXml` are required. All other arguments are optional.

Available arguments:

- `--appProject=...`
- `--contextXml=...`
- `--webappDir=...`
- `--classesDir=...`
- `--catalinaBase=...`
- `--contextPath=...`
- `--host=...`
- `--port=...`
- `--reloadable=true|false`
- `--sharedLibDir=dir1<path-separator>dir2`

Notes:

- If `--contextPath` is omitted, the launcher derives it from the `context.xml` file name, so `backend.xml` becomes `/backend` and `ROOT.xml` becomes the root context.
- `--sharedLibDir` uses the current platform path separator: `;` on Windows and `:` on Unix-like systems.
- Application runtime jars discovered under the target project are mounted under `/WEB-INF/lib`. Jars from `--sharedLibDir` are loaded through the parent classloader and are intended for shared container-style libraries.
- If `--sharedLibDir` is omitted, the launcher looks for a sibling `lib` directory next to the application root inferred from `contextXml`, for example `<appProject>/lib` when `contextXml` is under `<appProject>/conf/Catalina/localhost`.
- `context.xml` parsing covers `Resource`, `Environment`, `Parameter`, and nested `Resources` entries for `PreResources`, `JarResources`, and `PostResources` when they use Tomcat `DirResourceSet`, `JarResourceSet`, or `FileResourceSet`.

## GitHub release

Tag the repository with a semantic version such as `v1.0.0`. The workflow in `.github/workflows/release.yml` builds all modules and publishes the three runnable jars as release assets.