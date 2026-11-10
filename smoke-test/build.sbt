// SPDX-License-Identifier: MIT
// A genuinely standalone sbt build — deliberately NOT referenced anywhere in the
// root purerest build.sbt, so `sbt projects`/`sbt compile`/`sbt test` at the
// repo root never sweeps this in. Its whole point is to simulate a real external
// consumer of purerest: a jar resolved from the local Ivy2 cache via an ordinary
// libraryDependencies entry, not the internal .dependsOn(purerestlib) project
// reference the main build's own order-service/inventory-service fixtures use.

ThisBuild / licenses := Seq(License.MIT)

// Read from -DpurerestVersion=<version>, never hardcoded: purerest's version is
// derived from git (sbt-dynver) in the main build and changes on every commit.
// See scripts/verify-purerest-consumption.sh, which resolves and passes this in.
val purerestVersion = sys.props.getOrElse(
  "purerestVersion",
  sys.error(
    "purerestVersion system property not set — run via " +
      "scripts/verify-purerest-consumption.sh, or pass -DpurerestVersion=<version> " +
      "(the output of `sbt purerestlib/version` in the main repo, after " +
      "`sbt purerestlib/publishLocal`)."
  )
)

// Opt-in: resolve purerestlib from this repo's GitHub Packages Maven registry
// instead of the local Ivy2 cache (sbt's default). Pass
// -DresolveFromGitHubPackages=true, plus GITHUB_ACTOR/GITHUB_TOKEN (a PAT with
// read:packages scope) in the environment — GitHub Packages requires auth to
// read Maven artifacts even from a public repo.
val resolveFromGitHubPackages =
  sys.props.get("resolveFromGitHubPackages").contains("true")

lazy val root = project
  .in(file("."))
  .settings(
    name := "purerest-consumer-smoke-test",
    scalaVersion := "3.9.0",
    resolvers ++= (
      if (resolveFromGitHubPackages)
        Seq("GitHub Packages" at "https://maven.pkg.github.com/beckfordp/purerest")
      else Seq.empty
    ),
    credentials ++= (
      if (resolveFromGitHubPackages)
        Seq(
          Credentials(
            "GitHub Package Registry",
            "maven.pkg.github.com",
            sys.env.getOrElse("GITHUB_ACTOR", ""),
            sys.env.getOrElse("GITHUB_TOKEN", "")
          )
        )
      else Seq.empty
    ),
    libraryDependencies ++= Seq(
      // The whole point of this build: purerest resolved as an ordinary published
      // artifact from the local Ivy2 cache, not this repo's internal ProjectRef.
      "io.github.beckfordp" %% "purerestlib" % purerestVersion,
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
    )
  )
