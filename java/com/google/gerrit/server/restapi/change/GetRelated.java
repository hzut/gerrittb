// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.restapi.change;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.RelatedChangeAndCommitInfo;
import com.google.gerrit.extensions.api.changes.RelatedChangesInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.CommonConverters;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.revwalk.RevCommit;

@Singleton
public class GetRelated implements RestReadView<RevisionResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Provider<InternalChangeQuery> queryProvider;
  private final PatchSetUtil psUtil;
  private final RelatedChangesSorter sorter;
  private final IndexConfig indexConfig;
  private final ChangeData.Factory changeDataFactory;

  @Inject
  GetRelated(
      Provider<InternalChangeQuery> queryProvider,
      PatchSetUtil psUtil,
      RelatedChangesSorter sorter,
      IndexConfig indexConfig,
      ChangeData.Factory changeDataFactory) {
    this.queryProvider = queryProvider;
    this.psUtil = psUtil;
    this.sorter = sorter;
    this.indexConfig = indexConfig;
    this.changeDataFactory = changeDataFactory;
  }

  @Override
  public Response<RelatedChangesInfo> apply(RevisionResource rsrc)
      throws RepositoryNotFoundException, IOException, NoSuchProjectException,
          PermissionBackendException {
    RelatedChangesInfo relatedChangesInfo = new RelatedChangesInfo();
    relatedChangesInfo.changes = getRelated(rsrc);
    return Response.ok(relatedChangesInfo);
  }

  private List<RelatedChangeAndCommitInfo> getRelated(RevisionResource rsrc)
      throws IOException, PermissionBackendException {
    Set<String> groups = getAllGroups(rsrc.getNotes(), psUtil);
    logger.atFine().log("groups = %s", groups);
    if (groups.isEmpty()) {
      return Collections.emptyList();
    }

    List<ChangeData> cds =
        InternalChangeQuery.byProjectGroups(
            queryProvider, indexConfig, rsrc.getChange().getProject(), groups);
    if (cds.isEmpty()) {
      return Collections.emptyList();
    }
    if (cds.size() == 1 && cds.get(0).getId().equals(rsrc.getChange().getId())) {
      return Collections.emptyList();
    }
    List<RelatedChangeAndCommitInfo> result = new ArrayList<>(cds.size());

    boolean isEdit = rsrc.getEdit().isPresent();
    PatchSet basePs = isEdit ? rsrc.getEdit().get().getBasePatchSet() : rsrc.getPatchSet();
    logger.atFine().log("isEdit = %s, basePs = %s", isEdit, basePs);

    cds = reloadChangeIfStale(cds, rsrc.getChange(), basePs);

    for (RelatedChangesSorter.PatchSetData d : sorter.sort(cds, basePs)) {
      PatchSet ps = d.patchSet();
      RevCommit commit;
      if (isEdit && ps.id().equals(basePs.id())) {
        // Replace base of an edit with the edit itself.
        ps = rsrc.getPatchSet();
        commit = rsrc.getEdit().get().getEditCommit();
        logger.atFine().log(
            "Replaced base of edit (patch set %s, commit %s) with edit (patch set %s, commit %s)",
            d.patchSet().id(), d.commit(), ps.id(), commit);
      } else {
        commit = d.commit();
      }
      result.add(newChangeAndCommit(rsrc.getProject(), d.data().change(), ps, commit));
    }

    if (result.size() == 1) {
      RelatedChangeAndCommitInfo r = result.get(0);
      if (r.commit != null && r.commit.commit.equals(rsrc.getPatchSet().commitId().name())) {
        return Collections.emptyList();
      }
    }
    return result;
  }

  @VisibleForTesting
  public static Set<String> getAllGroups(ChangeNotes notes, PatchSetUtil psUtil) {
    return psUtil.byChange(notes).stream().flatMap(ps -> ps.groups().stream()).collect(toSet());
  }

  private List<ChangeData> reloadChangeIfStale(
      List<ChangeData> changeDatasFromIndex, Change wantedChange, PatchSet wantedPs) {
    checkArgument(
        wantedChange.getId().equals(wantedPs.id().changeId()),
        "change of wantedPs (%s) doesn't match wantedChange (%s)",
        wantedPs.id().changeId(),
        wantedChange.getId());

    List<ChangeData> changeDatas = new ArrayList<>(changeDatasFromIndex.size() + 1);
    changeDatas.addAll(changeDatasFromIndex);

    // Reload the change in case the patch set is absent.
    changeDatas.stream()
        .filter(
            cd -> cd.getId().equals(wantedPs.id().changeId()) && cd.patchSet(wantedPs.id()) == null)
        .forEach(ChangeData::reloadChange);

    if (changeDatas.stream().noneMatch(cd -> cd.getId().equals(wantedPs.id().changeId()))) {
      // The change of the wanted patch set is missing in the result from the index.
      // Load it from NoteDb and add it to the result.
      changeDatas.add(changeDataFactory.create(wantedChange));
    }

    return changeDatas;
  }

  static RelatedChangeAndCommitInfo newChangeAndCommit(
      Project.NameKey project, @Nullable Change change, @Nullable PatchSet ps, RevCommit c) {
    RelatedChangeAndCommitInfo info = new RelatedChangeAndCommitInfo();
    info.project = project.get();

    if (change != null) {
      info.changeId = change.getKey().get();
      info._changeNumber = change.getChangeId();
      info._revisionNumber = ps != null ? ps.number() : null;
      PatchSet.Id curr = change.currentPatchSetId();
      info._currentRevisionNumber = curr != null ? curr.get() : null;
      info.status = ChangeUtil.status(change).toUpperCase(Locale.US);
    }

    info.commit = new CommitInfo();
    info.commit.commit = c.name();
    info.commit.parents = Lists.newArrayListWithCapacity(c.getParentCount());
    for (int i = 0; i < c.getParentCount(); i++) {
      CommitInfo p = new CommitInfo();
      p.commit = c.getParent(i).name();
      info.commit.parents.add(p);
    }
    info.commit.author = CommonConverters.toGitPerson(c.getAuthorIdent());
    info.commit.subject = c.getShortMessage();
    return info;
  }
}
