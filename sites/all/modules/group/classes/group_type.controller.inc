<?php
/**
 * @file
 * Defines the Entity API CRUD class for group types.
 */

/**
 * Controller for group type entities.
 */
class GroupTypeController extends EntityAPIControllerExportable {

  /**
   * Delete a group type.
   *
   * @see EntityAPIController::delete()
   */
  public function delete($ids, DatabaseTransaction $transaction = NULL) {
    $gids = array();

    foreach (group_types($ids) as $group_type) {
      // If an entity is deleted while it was flagged as ENTITY_IN_CODE, it
      // means the entity was either reverted or really deleted. By checking
      // for the 'is_rebuild' property, we know it was deleted from within
      // _entity_defaults_rebuild() which only deletes the entity if the
      // default it came from is no longer available. In any other case, we
      // are dealing with a revert or a manual deletion which will only result
      // in the entity being rebuilt upon next cache clear.
      $entity_in_code = entity_has_status('group_type', $group_type, ENTITY_IN_CODE);
      $entity_rebuilt = !empty($entity->is_rebuild);

      // Set this on the group type so other modules can use it.
      $group_type->is_revert = $entity_in_code && !$entity_rebuilt;

      // Clean up local roles before deletion.
      $group_type->is_deleted = TRUE;
      $group_type->removeRoles();

      // Gather the group ids of all groups of the deleted type.
      if (!$group_type->is_revert) {
        $groups = group_load_by_type($group_type->name);
        $gids = array_merge($gids, array_keys($groups));
      }
    }

    // Delete all groups of the group types that weren't being reverted.
    if (!empty($gids)) {
      group_delete_multiple($gids);
    }

    // Delete the group types after setting our flags so those flags are still
    // being passed on to other modules implementing hook_group_delete().
    parent::delete($ids, $transaction);

    // Rebuild the menu cache so the group/add page works.
    menu_rebuild();
  }

  /**
   * Save a group type.
   *
   * @see EntityAPIController::save()
   */
  public function save($entity, DatabaseTransaction $transaction = NULL) {
    // When we are dealing with an imported Group Type, we need to take care
    // of the Group Role entities that were imported along with it.
    if (!empty($entity->is_new) && !empty($entity->roles)) {
      // Create the group roles that were imported along with the type.
      foreach ($entity->roles as $role) {
        $group_role = group_role_import(entity_var_json_export($role));
        group_role_save($group_role);
      }

      // Remove the roles property before saving the group type.
      unset($entity->roles);
    }

    // Save the group type after manipulating the roles and permissions
    // so that those functions don't incorrectly flag this group type
    // as ENTITY_CUSTOM.
    $return = parent::save($entity, $transaction);

    // Rebuild the menu cache so the group/add page works.
    menu_rebuild();

    return $return;
  }

  /**
   * Create a group type.
   *
   * We first set up the values that are specific to the group type schema
   * but then also run the EntityAPIControllerExportable counterpart.
   *
   * @param $values
   *   An array of values to set, keyed by property name.
   *
   * @return
   *   A new instance of the group type entity type.
   */
  public function create(array $values = array()) {
    // Provide defaults that are needed in group_type_form().
    $values += array(
      'name' => '',
      'label' => '',
      'outsider_permissions' => array(),
      'member_permissions' => array(),
    );

    return parent::create($values);
  }

  /**
   * Export a group type.
   *
   * Exports a group type and its group roles.
   */
  public function export($entity, $prefix = '') {
    // Only add special export logic on existing entities. Features likes to
    // run on-the-fly entities through GroupTypeController::export(), which
    // may cause issues when determining the feature's state.
    if (empty($entity->is_new)) {
      // Get the GroupRole controller.
      $controller = entity_get_controller('group_role');

      // Add an empty array to always have the 'roles' property on exports.
      $entity->roles = array();

      // Add group roles to the group type.
      foreach ($entity->getRoles(FALSE) as $group_role) {
        // Global group roles are never exported along with a group type.
        if ($group_role->global) {
          continue;
        }

        $entity->roles[] = drupal_json_decode($controller->export($group_role, $prefix));
      }
    }

    return parent::export($entity, $prefix);
  }
}
