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

package com.google.gerrit.server.group;

import com.google.gerrit.common.errors.NoSuchGroupException;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.AcceptsCreate;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ChildCollection;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountsCollection;
import com.google.gerrit.server.group.AddMembers.PutMember;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;

@Singleton
public class MembersCollection
    implements ChildCollection<GroupResource, MemberResource>, AcceptsCreate<GroupResource> {
  private final DynamicMap<RestView<MemberResource>> views;
  private final Provider<ListMembers> list;
  private final AccountsCollection accounts;
  private final Groups groups;
  private final Provider<ReviewDb> db;
  private final AddMembers put;

  @Inject
  MembersCollection(
      DynamicMap<RestView<MemberResource>> views,
      Provider<ListMembers> list,
      AccountsCollection accounts,
      Groups groups,
      Provider<ReviewDb> db,
      AddMembers put) {
    this.views = views;
    this.list = list;
    this.accounts = accounts;
    this.groups = groups;
    this.db = db;
    this.put = put;
  }

  @Override
  public RestView<GroupResource> list() throws ResourceNotFoundException, AuthException {
    return list.get();
  }

  @Override
  public MemberResource parse(GroupResource parent, IdString id)
      throws MethodNotAllowedException, AuthException, ResourceNotFoundException, OrmException,
          IOException, ConfigInvalidException {
    AccountGroup group = parent.toAccountGroup();
    if (group == null) {
      throw new MethodNotAllowedException();
    }

    IdentifiedUser user = accounts.parse(TopLevelResource.INSTANCE, id).getUser();
    if (parent.getControl().canSeeMember(user.getAccountId()) && isMember(group, user)) {
      return new MemberResource(parent, user);
    }
    throw new ResourceNotFoundException(id);
  }

  private boolean isMember(AccountGroup group, IdentifiedUser user)
      throws OrmException, ResourceNotFoundException {
    AccountGroup.UUID groupUuid = group.getGroupUUID();
    try {
      return groups.isMember(db.get(), groupUuid, user.getAccountId());
    } catch (NoSuchGroupException e) {
      throw new ResourceNotFoundException(String.format("Group %s not found", groupUuid));
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public PutMember create(GroupResource group, IdString id) {
    return new PutMember(put, id.get());
  }

  @Override
  public DynamicMap<RestView<MemberResource>> views() {
    return views;
  }
}
