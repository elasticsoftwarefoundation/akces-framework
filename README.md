# Akces-Framework

Apache Kafka CQRS & Event Sourcing Framework

### to run tests it is required to have a docker deamon running (for instance colima)

```shell
colima start --runtime docker
```

### Release process

This project uses the Maven Release Plugin and GitHub Actions to create releases.\
Just run `mvn release:prepare release:perform` in the root to select the version to be released and create a
VCS tag.

GitHub Actions will start [the build process](https://github.com/elasticsoftwarefoundation/akces-framework/actions/workflows/maven-publish.yml).

If successful, the build will be automatically published to [Github Packages](https://maven.pkg.github.com/elasticsoftwarefoundation/akces-framework/).
