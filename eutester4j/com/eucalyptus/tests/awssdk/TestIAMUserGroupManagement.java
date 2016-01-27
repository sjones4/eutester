/*************************************************************************
 * Copyright 2009-2016 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.tests.awssdk;

import static com.eucalyptus.tests.awssdk.Eutester4j.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;
import com.amazonaws.services.identitymanagement.model.AddUserToGroupRequest;
import com.amazonaws.services.identitymanagement.model.CreateGroupRequest;
import com.amazonaws.services.identitymanagement.model.CreateUserRequest;
import com.amazonaws.services.identitymanagement.model.DeleteGroupRequest;
import com.amazonaws.services.identitymanagement.model.DeleteUserRequest;
import com.amazonaws.services.identitymanagement.model.GetGroupRequest;
import com.amazonaws.services.identitymanagement.model.GetGroupResult;
import com.amazonaws.services.identitymanagement.model.GetUserRequest;
import com.amazonaws.services.identitymanagement.model.GetUserResult;
import com.amazonaws.services.identitymanagement.model.Group;
import com.amazonaws.services.identitymanagement.model.ListGroupsRequest;
import com.amazonaws.services.identitymanagement.model.ListGroupsResult;
import com.amazonaws.services.identitymanagement.model.ListUsersRequest;
import com.amazonaws.services.identitymanagement.model.ListUsersResult;
import com.amazonaws.services.identitymanagement.model.NoSuchEntityException;
import com.amazonaws.services.identitymanagement.model.RemoveUserFromGroupRequest;
import com.amazonaws.services.identitymanagement.model.UpdateGroupRequest;
import com.amazonaws.services.identitymanagement.model.UpdateUserRequest;
import com.amazonaws.services.identitymanagement.model.User;

/**
 * Test for management of IAM users and groups.
 * <p/>
 * Related issues:
 * <p/>
 * https://eucalyptus.atlassian.net/browse/EUCA-11952
 */
public class TestIAMUserGroupManagement {

  @Test
  public void userGroupManagementTest() throws Exception {
    testInfo(this.getClass().getSimpleName());
    getCloudInfo();
    final List<Runnable> cleanupTasks = new ArrayList<>();
    try {
      // Create user
      final String userName = NAME_PREFIX + "UserTest";
      print( "Creating user: " + userName );
      youAre.createUser( new CreateUserRequest( )
          .withUserName( userName )
          .withPath( "/path/" ) );
      cleanupTasks.add( new Runnable() {
        @Override
        public void run() {
          print( "Deleting user: " + userName );
          youAre.deleteUser( new DeleteUserRequest()
              .withUserName( userName ) );
        }
      } );

      // Get user
      print( "Getting user: " + userName );
      {
        final GetUserResult getUserResult =
            youAre.getUser( new GetUserRequest() .withUserName( userName ) );
        assertThat( getUserResult.getUser() != null, "Expected user" );
        assertThat( userName.equals( getUserResult.getUser().getUserName() ), "Unexpected user name" );
        assertThat( "/path/".equals( getUserResult.getUser().getPath() ), "Unexpected user path" );
        assertThat( getUserResult.getUser().getUserId() != null, "Expected ID" );
        assertThat( getUserResult.getUser().getArn() != null, "Expected ARN" );
        assertThat( getUserResult.getUser().getCreateDate() != null, "Expected created date" );
      }

      // Update user
      print( "Updating path for user: " + userName );
      youAre.updateUser( new UpdateUserRequest( ).withUserName( userName ).withNewPath( "/path/updated/" ) );

      print( "Getting user to check path: " + userName );
      {
        final GetUserResult getUserResult =
            youAre.getUser( new GetUserRequest( ) .withUserName( userName ) );
        assertThat( getUserResult.getUser() != null, "Expected user" );
        assertThat( "/path/updated/".equals( getUserResult.getUser().getPath() ), "Unexpected updated user path" );
      }

      // Create group
      final String groupName = NAME_PREFIX + "GroupTest";
      print( "Creating group: " + groupName );
      youAre.createGroup( new CreateGroupRequest( )
          .withGroupName( groupName )
          .withPath( "/path/" ) );
      cleanupTasks.add( new Runnable() {
        @Override
        public void run() {
          print( "Deleting group: " + groupName );
          youAre.deleteGroup( new DeleteGroupRequest()
              .withGroupName( groupName ) );
        }
      } );

      // Get group
      print( "Getting group: " + groupName );
      {
        final GetGroupResult getGroupResult =
            youAre.getGroup( new GetGroupRequest( ).withGroupName( groupName ) );
        assertThat( getGroupResult.getGroup() != null, "Expected group" );
        assertThat( groupName.equals( getGroupResult.getGroup().getGroupName() ), "Unexpected group name" );
        assertThat( "/path/".equals( getGroupResult.getGroup().getPath() ), "Unexpected group path" );
        assertThat( getGroupResult.getGroup().getGroupId() != null, "Expected ID" );
        assertThat( getGroupResult.getGroup().getArn() != null, "Expected ARN" );
        assertThat( getGroupResult.getGroup().getCreateDate() != null, "Expected created date" );
      }

      // Update group
      print( "Updating path for group: " + userName );
      youAre.updateGroup( new UpdateGroupRequest( ).withGroupName( groupName ).withNewPath( "/path/updated/" ) );

      print( "Getting group to check path: " + userName );
      {
        final GetGroupResult getGroupResult =
            youAre.getGroup( new GetGroupRequest( ).withGroupName( groupName ) );
        assertThat( getGroupResult.getGroup() != null, "Expected group" );
        assertThat( "/path/updated/".equals( getGroupResult.getGroup().getPath() ), "Unexpected updated group path" );
      }

      // Add user to group
      print( "Adding user to group" );
      youAre.addUserToGroup( new AddUserToGroupRequest( )
          .withUserName( userName )
          .withGroupName( groupName )
      );

      print( "Adding user to group (ensure idempotent)" );
      youAre.addUserToGroup( new AddUserToGroupRequest( )
          .withUserName( userName )
          .withGroupName( groupName )
      );

      // Remove user from group
      print( "Removing user from group" );
      youAre.removeUserFromGroup( new RemoveUserFromGroupRequest( )
          .withUserName( userName )
          .withGroupName( groupName )
      );

      print( "Removing user from group (ensure idempotent)" );
      youAre.removeUserFromGroup( new RemoveUserFromGroupRequest( )
          .withUserName( userName )
          .withGroupName( groupName )
      );

      // List users
      print( "Listing users to verify user present: " + userName );
      {
        final ListUsersResult listUsersResult = youAre.listUsers();
        boolean foundUser = isUserPresent( userName, listUsersResult.getUsers() );
        assertThat( foundUser, "User not found in listing" );
      }

      // List users with path
      print( "Listing users by path to verify user present: " + userName );
      {
        final ListUsersResult listUsersResult =
            youAre.listUsers( new ListUsersRequest().withPathPrefix( "/path" ) );
        boolean foundUser = isUserPresent( userName, listUsersResult.getUsers() );
        assertThat( foundUser, "User not found in listing for path" );
      }

      // Delete user
      print( "Deleting user: " + userName );
      youAre.deleteUser( new DeleteUserRequest().withUserName( userName ) );

      // List users (check deleted)
      print( "Listing users to check deletion of user: " + userName  );
      {
        final ListUsersResult listUsersResult = youAre.listUsers();
        boolean foundUser = isUserPresent( userName, listUsersResult.getUsers() );
        assertThat( !foundUser, "User found in listing after deletion" );
      }

      // List groups
      print( "Listing groups to verify group present: " + groupName );
      {
        final ListGroupsResult listGroupsResult = youAre.listGroups();
        boolean foundGroup = isGroupPresent( groupName, listGroupsResult.getGroups() );
        assertThat( foundGroup, "Group not found in listing" );
      }

      // List groups with path
      print( "Listing groups by path to verify group present: " + groupName );
      {
        final ListGroupsResult listGroupsResult =
            youAre.listGroups( new ListGroupsRequest().withPathPrefix( "/path" ) );
        boolean foundGroup = isGroupPresent( groupName, listGroupsResult.getGroups() );
        assertThat( foundGroup, "Group not found in listing for path" );
      }

      // Delete group
      print( "Deleting group: " + groupName );
      youAre.deleteGroup( new DeleteGroupRequest().withGroupName( groupName ) );

      // List groups (check deleted)
      print( "Listing groups to check deletion of group: " + groupName  );
      {
        final ListGroupsResult listGroupsResult = youAre.listGroups();
        boolean foundGroup = isGroupPresent( groupName, listGroupsResult.getGroups() );
        assertThat( !foundGroup, "Group found in listing after deletion" );
      }

      print("Test complete");
    } finally {
      // Attempt to clean up anything we created
      Collections.reverse(cleanupTasks);
      for (final Runnable cleanupTask : cleanupTasks) {
        try {
          cleanupTask.run();
        } catch (NoSuchEntityException e) {
          print("Entity not found during cleanup.");
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }


  private boolean isUserPresent(final String userName, final List<User> users) {
    boolean foundUser = false;
    if (users != null) for (final User user : users) {
      foundUser = foundUser || userName.equals(user.getUserName());
    }
    return foundUser;
  }

  private boolean isGroupPresent(final String groupName, final List<Group> groups) {
    boolean foundGroup = false;
    if (groups != null) for (final Group group : groups) {
      foundGroup = foundGroup || groupName.equals(group.getGroupName());
    }
    return foundGroup;
  }
}
