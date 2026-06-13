# Vendored Maven Artifacts

This directory mirrors the artifacts that are required for `org.dcache:nfs4j-basic-client:0.27.2`
but are not published to Maven Central.

The CI build depends on these artifacts through the normal Maven coordinates, but Gradle resolves
them from this repo-local Maven repository before checking remote repositories. This avoids relying
on `https://download.dcache.org/nexus/repository/public/`, which timed out repeatedly from GitHub
Actions during the shipped debug variant build.

Mirrored artifacts:

- `com.sleepycat:je:7.3.7`
- `org.dcache:nfs4j:0.27.2`
- `org.dcache:nfs4j-basic-client:0.27.2`
- `org.dcache:nfs4j-core:0.27.2`
- `org.dcache:oncrpc4j:3.3.0`
- `org.dcache:oncrpc4j-core:3.3.0`

Source repository: `https://download.dcache.org/nexus/repository/public/`
