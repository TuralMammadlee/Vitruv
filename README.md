# Vitruv Framework (Local Branch)

This repository contains the core Vitruv framework for view-based model development.  
In this codebase, the shared model state is handled by `VirtualModel`, and consistency is maintained by propagating changes across related models.

The current branch is centered on VSUM merge and conflict behavior in `vsum`, especially around branch-aware storage, semantic conflict detection, and conflict classification data that will be used by upcoming auto-resolution flow.

## Repository structure

The root build contains five modules: `views`, `vsum`, `testutils`, `applications`, and `p2wrappers`.

`views` provides view abstractions and implementations.  
`vsum` contains the virtual model runtime, branch operations, changelog handling, and merge/conflict logic.  
`testutils` contains shared test utilities.  
`applications` contains application-level integration and registration code.  
`p2wrappers` contains wrapper artifacts used by the framework build.

## What is implemented in this branch so far

The main merge flow is handled by `MergeManager`, which performs Git merges through JGit, records merge metadata, and tracks both deletion and update conflicts from semantic changelogs. Conflict outcomes are represented through `ModelMergeResult`, including successful, fast-forward, conflicting, and failed merge states.

Commit flow is handled by `CommitManager`. It stages model files, writes branch metadata updates, creates commits, and triggers post-commit processing. When semantic tracking is attached, commit-time semantic changelog data is also written through `SemanticChangelogManager`.

`VsumFileSystemLayout` now supports two storage modes. Inside a Git repository it uses branch-aware paths under `.vitruvius/vsum/<branch>`. Outside a Git repository it falls back to a legacy `vsum` folder, so non-Git test environments still work correctly. This behavior is covered by `VsumFileSystemLayoutTest`.

For update conflicts, `UpdateConflict` now stores explicit origin permutations (`O_O`, `O_C`, `C_O`, `C_C`, `UNKNOWN_UNKNOWN`) and a fundamental conflict type (`SYNTACTIC` or `SEMANTIC`). These tags are computed from semantic change entries and are used directly in severity calculation. `UpdateConflictAnalyzer` now logs the computed permutation, type, and resulting severity when conflicts are detected.

At this stage, conflict resolution is still manual once a merge is marked `CONFLICTING`. The classification and severity wiring above is in place to support the next step, which is controlled auto-resolution paths.

The workflow is also available in the activity diagram as SVG. 

## Dependency setup

This branch currently uses a local `Vitruv-Change` version in `pom.xml`:
`vitruv-change.version=3.2.4-mybranch-SNAPSHOT`.

If this version is not present in your local Maven repository, build and install the matching `Vitruv-Change` branch first. Without that, Maven dependency resolution will fail even if this repository itself is correct. The reason for it because I was getting errors on mvn build so I did as a workaround. Changing `vitruv-change.version=3.2.4-mybranch-SNAPSHOT` to `vitruv-change.version=3.2.4-SNAPSHOT` would solve the issue

## Build and test

To build the whole project:

```bash
mvn clean install
```

To work only on VSUM:

```bash
mvn -pl vsum test
```
