# Vitruv Framework (Local Branch)

This repository contains the core Vitruv framework for view based model development.  
In this codebase, the shared model state is handled by `VirtualModel`, and consistency is maintained by propagating changes across related models.

The current branch is centered on VSUM merge and conflict behavior in `vsum`, especially around branch aware storage, semantic conflict detection, weighted impact for deletion clearance, tiered automatic handling for update clashes, and the optional domain validation hook where project specific defaults will plug in.

## Repository structure

The root build contains five modules: `views`, `vsum`, `testutils`, `applications`, and `p2wrappers`.

`views` provides view abstractions and implementations.  
`vsum` contains the virtual model runtime, branch operations, changelog handling, and merge and conflict logic.  
`testutils` contains shared test utilities.  
`applications` contains application level integration and registration code.  
`p2wrappers` contains wrapper artifacts used by the framework build.

Most of this branch work lives under `vsum/src/main/java/tools/vitruv/framework/vsum/branch` and helpers such as `vsum/helper/VsumFileSystemLayout.java`.

## What is implemented in this branch so far

### Merge and commit pipelines

The main merge flow is handled by `MergeManager`. It drives JGit merges, distinguishes fast forward from merge commits versus conflicted merges, wraps the outcome in `ModelMergeResult`, and can write merge metadata and triggers for watchers. After a conflicting merge outcome, when semantic changelogs can be loaded, `MergeManager` stores the analyzed deletion and update conflict lists separately so callers can inspect them (`getLastDeletionConflicts()`, `getLastUpdateConflicts()`) before any resolution passes.

Those conflicts are inferred from changelog JSON maintained by commit time tooling, not from live EMF model instances loaded for diffing everywhere. Semantically, that keeps merge time analysis predictable and aligns with changelog driven audit trails.

Commit flow runs through `CommitManager`: it stages conventional model artifact extensions and branch metadata updates, commits, and when semantic tracking is wired in it drains buffered changes into changelog shape via `SemanticChangelogManager` and fires post commit triggers for asynchronous downstream steps.

Git level merges that remain `CONFLICTING` always require normal Git resolution. This branch adds semantic overlays and optional automatic rules on top once changelogs are coherent. It does not replace fixing conflict markers or committing the merged tree yourself.

### Branch aware VSUM storage

`VsumFileSystemLayout` chooses where persistence lives depending on whether the project folder hosts a usable Git checkout. Inside a repo with commits, persisted VSUM data goes under `.vitruvius/vsum/` in a subfolder named for the current Git branch, so isolation between branches mirrors Git's context. Outside that shape (temporary directories in tests without Git, loose folders for experiments), paths fall back to a single `vsum/` directory so tooling does not require `git init` just to satisfy unit scenarios. Constructors that take an explicit branch name force branch aware mode when code already knows the branch, for example switching inside `BranchAwareVirtualModel`.

### Update versus update conflicts (detection and classification)

Semantic update conflicts are surfaced by `UpdateConflictAnalyzer` when two changelog documents each contain a modifying semantic change targeting the **same element UUID** and **same feature name**. Only change types counted as edits to attributes or references participate. Lifecycle only entries are skipped because they behave differently structurally than two writers racing on one feature.

Whenever such a clash is detected, the code constructs an `UpdateConflict` DTO. The constructor derives two tags from both sides synchronously, so callers cannot forget to pass them, in order to stabilize downstream decisions. `**OriginPermutation`** records which combination of ORIGINAL versus CONSEQUENTIAL appears on the incoming branch versus the current branch (`O_O`, `O_C`, `C_O`, `C_C`), with a guarded `**UNKNOWN_UNKNOWN**` whenever either side lacks a trusted origin marker. `**FundamentalConflictType**` compresses semantic change semantics into `**SYNTACTIC**` roughly when both sides manipulate primitive attribute payload only, versus `**SEMANTIC**` whenever reference, containment shaped, or unresolved structure affecting work appears on either side. That split exists because overwriting attribute values is usually narrower in blast radius than disputing topology of references.

Severity for updates is computed inside `UpdateConflict.getSeverity()` from the permutation and fundamental type together. Mixed origin cases stay at moderate severity because deterministic “favor the human edit” mitigation is expected afterward. Pure consequential on consequential clashes rate higher risk because consistency engines disagreed rather than humans. Original on original climbs into high tiers when topology or references are disputed, not merely attribute literals. Unknown origins stay conservatively middling rather than escalating blindly.

Diagnostics from the analyzer deliberately log permutation, fundamental type, and resolved severity beside branch names so merges can trace how each conflict graded without dumping full JSON payloads.

### Deletion conflicts (weighted impact and routing into roles)

`DeletionConflictAnalyzer` aggregates delete versus update situations into `DeletionConflict` objects whose `affectedUpdates` catalogue every semantic edit on the survivor branch tied to tombstoned or removed structure. Acceptance of deletion means those deltas vanish, which is precisely what makes the conflict existential.

Earlier behavior mapped severity tiers straight from `**affectedUpdates.size()**`. Here the code adds `**getWeightedImpact()**`: each doomed delta contributes an integer derived from `**ChangeOrigin**`. Human `**ORIGINAL**` edits carry heavier weight than `**CONSEQUENTIAL**` propagated edits, and `**UNKNOWN**` is aligned with consequential so missing origin metadata does not over rate impact.

`**getSeverity()**` feeds that summed score into the familiar `**SeverityThresholds**` ladders through `**ConflictSeverity.fromLostUpdateCount(...)**`. Administrators keep one JSON vocabulary for cutoff numbers. Only the numerator changes from blunt head count to aggregated risk.

`**getLostUpdateCount()**` still returns `**affectedUpdates.size()**` on purpose. Role policies also impose a ceiling on **how many** updates may be discarded in one decision, even when weighted severity stays modest, so count based guardrails and impact based severity stay orthogonal instead of collapsed into one number.

Clearance ultimately still runs through `**MergePolicy.canApproveDeletion**`, combining maximum allowed severity against `**DeletionConflict#getSeverity**` and update cap checks against `**getLostUpdateCount()**`. No second approval channel was introduced.

### Automatic tiers for unresolved update versus update clashes

Not every contradictory update pair should wait on a modal when precedence is already spelled out elsewhere. `**UpdateConflictResolver**` walks the `**UpdateConflict**` instances from the latest merge pass in deterministic order.

First, when `**OriginPermutation.isMixedOrigin()**` applies, `**getPreferredEntry()**` immediately picks the human ORIGINAL lineage so propagated edits lose without user prompts. Second, only if that rule does not apply, `**MergeManager`'s optional `**DomainValidator**` may return a `**suggestResolution**` choice, typically project specific dominance such as pinned schema versions. Third, anything still unanswered is grouped under `**AutoResolutionOutcome#getUnresolved**` for UI workflows or ticketing.

Those tiers classify **which side should win**, not mutate model files themselves. Applying the winners still belongs to whoever runs your merge completion pipeline. `**MergeManager.setDomainValidator`** installs collaborator logic. `**DomainValidator.NONE**` retains prior “no domain default” behavior when nothing is wired.

Regression coverage lives in `**DeletionConflictTest**` and `**UpdateConflictResolverTest**`, with existing `**UpdateConflictAnalyzerTest**` and `**MergeManagerTest**` covering surrounding merge and analyzer behavior.

### Activity diagram artifact

The workflow is also available in the activity diagram as SVG.

## Dependency setup

This branch pins the Vitruv Change reactor version inside the root `pom.xml` to a snapshot you built locally (the filename already documents the usual placeholder name for that fork build). If that snapshot is absent from your local Maven repository, build and install the matching Vitruv Change branch first. Without it, Maven dependency resolution fails even though this checkout may be fine. Prefer upstream only builds by restoring the Vitruv Change version entry in the root `pom.xml` to whatever the upstream project publishes as its snapshot.

## Build and test

To build the whole project:

```bash
mvn clean install
```

To work only on VSUM:

```bash
cd vsum
mvn test
```

