<?php
/**
 * @file
 * Defines the Entity API CRUD class for group roles.
 */

/**
 * Controller for group role entities.
 */
class GroupRoleController extends EntityAPIControllerExportable {

  /**
   * Delete a group role.
   *
   * @see EntityAPIController::delete()
   */
  public function delete($ids, DatabaseTransaction $transaction = NULL) {
    if (!empty($ids)) {
      foreach (group_roles($ids) as $group_role) {
        // Invalidate the parent group type's GroupRole cache.
        $group_role->invalidateTypeCache();

        // Flag parent type as ENTITY_CUSTOM.
        $group_role->flagTypeCustom();
      }
    }

    parent::delete($ids, $transaction);
  }

  /**
   * Save a group role.
   *
   * @see EntityAPIController::save()
   */
  public function save($entity, DatabaseTransaction $transaction = NULL) {
    $is_new = !empty($entity->is_new);
    $is_special = in_array($entity->name, array('outsider', 'member'));

    // Invalidate the parent type's GroupRole cache whenever we save a new
    // group role or alter a special group role (outsider or member). The
    // latter case is required because special group roles are never actually
    // saved to the db and thus need to be rebuilt after every change to avoid
    // serving outdated special roles from the cache.
    if ($is_new || $is_special) {
      $entity->invalidateTypeCache();
    }

    // If we save a special group role (outsider or member), we don't actually
    // save the role but attach its permissions back onto the group type.
    if ($is_special) {
      $key = $entity->name . '_permissions';

      $group_type = group_type_load($entity->type);
      $group_type->$key = $entity->permissions;
      group_type_save($group_type);

      return;
    }

    // If we save a group role we call GroupRole::flagTypeCustom() which in turn
    // will check whether or not there is a parent group type that needs to be
    // flagged as ENTITY_CUSTOM.
    $entity->flagTypeCustom();

    return parent::save($entity, $transaction);
  }

  /**
   * Create a group role.
   *
   * We first set up the values that are specific to the group role schema
   * but then also run the EntityAPIControllerExportable counterpart.
   *
   * @param $values
   *   An array of values to set, keyed by property name.
   *
   * @return
   *   A new instance of the group role entity type.
   */
  public function create(array $values = array()) {
    // Provide defaults that are needed in group_role_form().
    $values += array(
      'type' => '',
      'name' => '',
      'label' => '',
      'global' => 1,
      'permissions' => array(),
    );

    return parent::create($values);
  }
}
