<?php
/**
 * @file
 * Group role permissions admin UI.
 */

/**
 * Builds the role permission page.
 *
 * @param GroupType $group_type
 *   The group type to show the permissions page for.
 * @param GroupRole $group_role
 *   (optional) A single group role to show the page for.
 *   When $group_role is provided, $group_type is ignored.
 */
function group_permission_form($form, &$form_state, $group_type, $group_role = NULL) {
  // Define whether we're on a group type overview page or not.
  $type_page = !empty($group_type) && empty($group_role);

  // Store if the permission descriptions should be hidden.
  $hide = system_admin_compact_mode();

  // Retrieve role names for columns.
  $group_roles = !$type_page ? array($group_role->name => $group_role) : $group_type->getRoles();

  // Store $group_roles for use in the theme function and submit handler.
  $form['roles'] = array(
    '#type' => 'value',
    '#value' => $group_roles,
  );

  // Shortcut to the theme function if no roles are available.
  if (empty($group_roles)) {
    return $form;
  }

  // Fetch permissions for all roles or the one selected role.
  $role_permissions = array();
  foreach ($group_roles as $group_role) {
    $role_permissions[$group_role->name] = $group_role->permissions;
  }

  // Get a list of all the modules implementing a hook_group_permission()
  // and sort by display name.
  $modules = array();
  $module_info = system_get_info('module');

  foreach (module_implements('group_permission') as $module) {
    $modules[$module] = $module_info[$module]['name'];
  }
  asort($modules);

  // Populate the checkbox options and rows for every possible permission.
  $options = array();
  foreach ($modules as $module => $display_name) {
    if ($permissions = module_invoke($module, 'group_permission')) {
      // Add the module name first so we can show a separating row.
      $form['permissions'][] = array(
        '#markup' => $module_info[$module]['name'],
        '#id' => $module,
      );

      // Then add a row for every permission defined by the module.
      foreach ($permissions as $pname => $permission) {
        // Add the permission name to the checkbox options.
        $options[$pname] = '';

        // Set the warning message, if any.
        $warning = !empty($permission['restrict access'])
          ? t('Warning: Give to trusted roles only; this permission has security implications.')
          : '';

        // Fill in default values for the permission.
        $permission += array(
          'description' => '',
          'restrict access' => FALSE,
          'warning' => $warning,
        );

        // Theme the permission name and description.
        $form['permissions'][$pname] = array(
          '#type' => 'item',
          '#markup' => $permission['title'],
          '#description' => theme('group_permission_description', array(
            'permission' => $permission,
            'hide' => $hide,
          )),
        );

        foreach ($group_roles as $rname => $group_role) {
          // Save which checkboxes should already be checked.
          if (in_array($pname, $role_permissions[$rname])) {
            $status[$rname][] = $pname;
          }
        }
      }
    }
  }

  // Keep track of every role's disabled status.
  $all_disabled = TRUE;

  foreach ($group_roles as $rname => $group_role) {
    $role_fixed = entity_has_status('group_role', $group_role, ENTITY_FIXED);

    // Check whether this role should be disabled. This is the case for either
    // fixed global roles, local roles that belong to a fixed group type or
    // global roles displayed on the group type permissions overview.
    $disabled = $group_role->global && ($type_page || $role_fixed);
    if (!empty($group_role->type) && $group_type = group_type_load($group_role->type)) {
      $disabled = $disabled || entity_has_status('group_type', $group_type, ENTITY_FIXED);
    }

    // Add this role's disabled status to the tracker.
    $all_disabled = $all_disabled && $disabled;

    // Build the checkboxes for every role.
    $form['checkboxes'][$rname] = array(
      '#type' => 'checkboxes',
      '#options' => $options,
      '#default_value' => isset($status[$rname]) ? $status[$rname] : array(),
      '#attributes' => array('class' => array('rid-' . $rname)),
      '#disabled' => $disabled,
    );

    // Add the role names to the table.
    $form['roles'][$rname] = array(
      '#markup' => check_plain($group_role->label),
      '#tree' => TRUE,
    );
  }

  if (!$all_disabled) {
    $form['actions'] = array('#type' => 'actions');
    $form['actions']['submit'] = array(
      '#type' => 'submit',
      '#value' => t('Save permissions'),
    );
  }

  // @todo: Do we want our own version?
  $form['#attached']['js'][] = drupal_get_path('module', 'user') . '/user.permissions.js';

  return $form;
}

/**
 * Submit handler for group_permission_form().
 *
 * Saves the (de)selected permissions for a group type.
 */
function group_permission_form_submit($form, &$form_state) {
  foreach ($form_state['values']['roles'] as $rname => $group_role) {
    $group_role->changePermissions($form_state['values'][$rname]);
  }

  drupal_set_message(t('The changes have been saved.'));
}
