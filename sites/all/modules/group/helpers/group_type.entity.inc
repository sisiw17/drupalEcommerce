<?php
/**
 * @file
 * Entity API related helper functions for group types.
 */

/**
 * Load group types.
 *
 * @param array $names
 *   (optional) An array of machine names to filter on.
 *
 * @return array
 *   An array of GroupType objects, keyed by their machine names.
 *
 * @see entity_load_multiple_by_name()
 */
function group_types($names = FALSE) {
  return entity_load_multiple_by_name('group_type', $names);
}

/**
 * Load a group type by name.
 */
function group_type_load($name) {
  $types = group_types(array($name));
  return reset($types);
}

/**
 * Load multiple group types by id.
 */
function group_type_load_multiple($tids = FALSE, $reset = FALSE) {
  return entity_load('group_type', $tids, array(), $reset);
}

/**
 * Import a group type.
 */
function group_type_import($export) {
  return entity_import('group_type', $export);
}

/**
 * Save a group type.
 */
function group_type_save($group_type) {
  return entity_save('group_type', $group_type);
}

/**
 * Delete a group type.
 */
function group_type_delete($group_type) {
  entity_delete('group_type', entity_id('group_type', $group_type));
}

/**
 * Delete multiple group types.
 */
function group_type_delete_multiple($tids) {
  entity_delete_multiple('group_type', $tids);
}
