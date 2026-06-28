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

### Conflict owner detection and owner-priority clearance

When a merge is conflicting, `MergeManager` runs `ConflictOwnerResolver` to detect the original author(s) of the conflicting changes. Ownership is derived purely from **JGit blame** against both merge participants (the source and target revisions), using the conflicting line ranges as hints with a whole-file fallback. Author identities are normalized to lowercased, trimmed Git emails. No `authorEmail` persistence feature is used for these decisions; ownership is computed on demand from blame only.

The aggregated owner set is attached to every detected `DeletionConflict` and `UpdateConflict` (`detectedOwners` plus an `ownerDetectionAvailable` flag), and `MergeManager.getLastDetectedConflictOwners()` exposes the owners of the most recent conflicting merge.

The permission model is **owner-priority with role fallback**, applied in `MergePolicy.canApproveDeletion`:

- if the current user is a detected owner of the conflict, they may resolve it directly;
- if they are not a detected owner, the existing role/severity/update-count checks apply;
- if owner information is unavailable, the system falls back fully to the existing role-based behavior.

### Owner escalation (clearance denied path)

When `MergePolicy.requiresEscalation()` is true for a deletion conflict (the current user's role cannot clear it), `MergeManager.resolveDeletionConflicts()` runs the clearance-denied escalation path instead of prompting the resolver immediately:

1. **Notify owner** — `OwnerNotifier` writes `.vitruvius/notifications/<source>-into-<target>-<elementUuid>.json` containing conflict details, affected elements, and the risk score (weighted impact, lost-update count, severity).
2. **Owner review** — `ConflictReviewService` builds and persists `.vitruvius/reviews/<source>-into-<target>-<elementUuid>.json` with conflict history (changelog entries from both branches), change-level state previews (`from`/`to` per affected update), and a severity report including the role-clearance gap.
3. **Owner decision** — the detected owner records approve/deny via `MergeManager.submitOwnerDecision(elementUuid, approve, rationale)`, persisted under `.vitruvius/owner-decisions/`.
4. **Outcome** — if the owner **approved**, the conflict resolves using the merge policy default (typically recover from ancestor) with the owner's rationale captured; if **denied** or **no decision** exists yet, the merge stays **BLOCKED** (`RESTRICT_DELETIONS`).

All escalation steps are also recorded in the merge session audit log (`.vitruvius/audit/`) via `AuditLogEntry` types `OWNER_NOTIFICATION`, `OWNER_REVIEW`, `OWNER_DECISION`, and `MERGE_BLOCKED`.

### Activity diagram artifact

The workflow is also available in the activity diagram as SVG.

## Dependency setup

This branch pins the Vitruv Change reactor version inside the root `pom.xml` to a snapshot you built locally (`3.2.4-mybranch-SNAPSHOT`). That snapshot must include `tools.vitruv.change.changederivation.persistence.DeltaPersistence` (used by `SemanticChangelogManager` for XMI delta snapshots). If it is absent from your local Maven repository (`~/.m2/repository/tools/vitruv/`), build and install the matching Vitruv Change branch first:

```bash
cd /path/to/Vitruv-Change
./mvnw clean install -DskipTests
```

Without that snapshot, compilation fails on `SemanticChangelogManager` even when the rest of the checkout is fine.

**Build with JDK 21.** Newer JDKs (24/25) break Lombok annotation processing in this project.

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

