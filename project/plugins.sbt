addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
// Derives purerest's version from git tags/commits (e.g. 0.1.0-3-abc1234) instead of
// a manually-maintained val, so it can be published without hand-bumping a version
// on every release.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")
// Load-test module (modules/load-test): drives Gatling simulations via the
// plugin's dedicated `Gatling` sbt configuration (`sbt loadTest/Gatling/test`),
// deliberately separate from the normal `test` task.
addSbtPlugin("io.gatling" % "gatling-sbt" % "4.13.3")
// Packages order-service/inventory-service as Docker images (JavaAppPackaging +
// DockerPlugin) for the local observability stack — see docker-compose.yml's
// "observability" profile.
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")
