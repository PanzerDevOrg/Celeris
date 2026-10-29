# Releasing

This page is for maintainers.

## Steps

1. Write the changelog. Copy the latest file in `docs/changelogs/` to a new name, such as `docs/changelogs/0.3.2.md`, and rewrite it.
2. Set the new version under `[mod]` in `mod.stonecutter.properties.toml`.
3. Commit and push. Every push runs a dry run. In that CI run, open the `release-preview` artifact and check the pages and files.
4. Create a tag with the same version, and push it:

```bash
git tag v0.3.2
git push origin v0.3.2
```

CI then publishes the release to GitHub, Modrinth and CurseForge, and updates the Maven repository. CI fails if the changelog file for the version is missing.

The shared build logic has a command that checks the changelog and the shared files, then pushes the tag. Run it from the bichal-build-logic folder:

```bash
./bichal release celeris --push
```

## Updating the wiki

The pages in `docs/wiki/` are the source for this wiki. The GitHub wiki is a separate git repository, at `https://github.com/bichal/Celeris.wiki.git`. After you change a page, copy the changed files into that repository, commit them, and push.
