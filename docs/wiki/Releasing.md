# Releasing

The README is the description on Modrinth and CurseForge too. To release: add `docs/changelogs/<version>.md`, set `version` under `[mod]` in
`mod.stonecutter.properties.toml`, commit, then push a matching tag:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

CI publishes the Maven repository, a GitHub release with every jar, and the Modrinth and CurseForge files. Every other
push runs the same release as a dry run and uploads a `release-preview` artifact with the pages as they would look.

[Back to Home](Home.md)
