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

### Owner escalation (the clearance-denied path)

Not every deletion conflict can be cleared by whoever happens to be running the merge. When a developer's role is not authorized to accept a high-impact deletion, the merge should not silently fall through to a default, and it should not simply stop with an unhelpful "blocked" message either. Instead, the conflict is handed to the person who actually made the affected work, and only escalated further if that person cannot or will not resolve it. This is the *clearance-denied* branch of the activity diagram, and it is implemented end to end in this branch.

The entry point is `MergePolicy.requiresEscalation()`. When it reports that the current user cannot clear a given `DeletionConflict`, `MergeManager.resolveDeletionConflicts()` routes that conflict into `handleEscalatedConflict()` rather than resolving it directly. From there the workflow follows the diagram: identify the owner, notify them, let them review, wait for their decision, and — if the decision never comes or is negative — escalate to a more senior role.

#### Identifying the right owner

The diagram calls for the *author of the affected original changes*, so ownership is resolved semantically rather than by raw file authorship. `ConflictOwnershipResolver` looks at the conflict's affected updates and, when any of them carry `ChangeOrigin.ORIGINAL`, takes the commit author of the branch that recorded those human edits as the owner. The same applies to an original deletion. This is deliberately different from the earlier blame-only approach: the goal is to reach the person whose intentional work is at risk, not merely whoever last touched a line in the file.

Because semantic information is not always available, the resolver degrades gracefully in three stages:

1. **Semantic owners** — commit authors behind the `ORIGINAL` changes involved in the conflict.
2. **Blame fallback** — the JGit blame owners already computed for the merge, used when no semantic owner can be determined.
3. **Role fallback** — if neither of the above yields anyone, `RoleManager.findFallbackOwnerIds()` assigns the conflict to the project's `METHODOLOGIST` users (or, failing that, an admin). This closes the "empty owner" dead end, where a conflict could previously end up blocked with nobody able to act on it.

Ownership is resolved **per conflict** rather than as a single merge-wide set, so different conflicts in the same merge can legitimately route to different people.

#### Notifying and preparing the review

Once an owner is known, `OwnerNotifier` writes a durable notification artifact under `.vitruvius/notifications/<source>-into-<target>-<elementUuid>.json`. It captures the conflict identity, the affected elements, and a risk summary (origin-weighted impact, lost-update count, and severity) so the owner can triage without opening the model. In parallel, `ConflictReviewService` assembles a review package under `.vitruvius/reviews/<source>-into-<target>-<elementUuid>.json` containing the conflict history from both branches, a `from`/`to` state preview for each affected update, and a severity report that spells out the role-clearance gap. Both artifacts are written idempotently, so repeated resolve passes do not produce duplicates or overwrite an owner's in-progress context.

#### The owner's decision

The decision itself (`d_owner` in the diagram) can be made two ways. In an interactive terminal, `OwnerEscalationResolver` presents the review summary directly to a detected owner and prompts for **Approve**, **Deny**, or **Skip**. On approval, the owner is not limited to a yes/no answer — they choose the actual resolution strategy, mirroring the manual resolver: `[R]` recover the deleted element from the shared ancestor, `[D]` accept the deletion and its lost updates, or `[S]` skip and leave it blocked. That chosen policy travels with the decision so the merge applies exactly what the owner intended rather than a fixed default.

In a headless or CI context, where no console is available, the same decision can be recorded out of band through `MergeManager.submitOwnerDecision(...)`. Both paths persist an `OwnerDecision` (including the optional chosen policy and a free-text rationale) under `.vitruvius/owner-decisions/`, and the next resolve pass reads it back.

#### Escalating when the owner cannot resolve it

If the owner denies the change, or if no decision has appeared within the configured window, the conflict does not just stay quietly blocked. `MergeManager` re-routes it to a senior role and records that fact as an `OwnerEscalation` (`ESCALATED_TO_SENIOR`) under `.vitruvius/owner-escalations/`, naming the `METHODOLOGIST` assignees who can now act. On a later pass, a user in that senior role picks the conflict up and resolves it through the normal resolver. The no-response window is controlled by `OwnerEscalationConfig` — a 72-hour default that can be overridden with the `vitruv.owner.response.timeout.hours` system property or the `VITRUV_OWNER_RESPONSE_TIMEOUT_HOURS` environment variable (a value of zero triggers immediate senior escalation in headless runs, which is convenient for tests).

#### Outcomes and audit trail

The path resolves to one of three outcomes: the owner **approves** and the merge applies their chosen policy with the rationale attached; the owner **denies** or **times out**, and the conflict is escalated to a senior role while the merge stays **BLOCKED** (`RESTRICT_DELETIONS`); or a senior resolver finally clears it. Every step along the way is appended to the merge-session audit log under `.vitruvius/audit/` through `AuditLogEntry` — `OWNER_NOTIFICATION`, `OWNER_REVIEW`, `OWNER_DECISION`, `OWNER_ESCALATION`, and `MERGE_BLOCKED` — so the full history of who was asked, what they decided, and why the merge was held is reconstructable after the fact.

#### Design notes

A few decisions are worth calling out for the report. Ownership is computed on demand and never trusted from a single merge-wide blob, which keeps per-conflict routing honest. Artifacts (notifications, reviews, decisions, escalations) are plain JSON on disk rather than an in-memory queue, so the workflow survives process restarts and works the same in interactive and headless environments. And the escalation is intentionally *policy-preserving*: it changes *who decides* and *when the merge is allowed to proceed*, but the actual model mutation still goes through the same deletion-resolution machinery used everywhere else, rather than a separate privileged code path.

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

