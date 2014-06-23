<?php

/**
 * @file
 * Custom checkoutt pane.
 */

/**
 * Custom checkout pane.
 * 
 * Function name should consist of <pane key>_checkout_form.
 */
function approve_process_pane_checkout_form($form, &$form_state, $checkout_pane, $order) {
  global $user;
  $pane_form = array();
  // Retrieve article node form.
  $type = 'page';
  $node = array('uid' => $user->uid, 'name' => (isset($user->name) ? $user->name : ''), 'type' => $type, 'language' => LANGUAGE_NONE);
 
  // Merge empty node with order node data.
  if (isset($order->data['node'])) {
    $node = array_merge($node, $order->data['node']);
  }
 
  $node = (object) $node;
 
  module_load_include('inc', 'node', 'node.pages');
 
  // Retrieve node form.
  $node_form_state = array();
  $node_form_state['build_info']['args'] = array($node);
  $node_form_state += form_state_defaults();
  $pane_form = drupal_retrieve_form($type . '_node_form', $node_form_state);
 
  // Hide some node form elements.
  $pane_form['actions']['submit']['#access'] = FALSE;
  $pane_form['actions']['preview']['#access'] = FALSE;
  $pane_form['author']['#access'] = FALSE;
  $pane_form['options']['#access'] = FALSE;
  $pane_form['revision_information']['#access'] = FALSE;
 
  return $pane_form;
}

/**
 * Custom checkout pane submit handler.
 *
 * Save node data to order.
 */
function approve_process_pane_checkout_form_submit($form, &$form_state, $checkout_pane, &$order) {
  $order->data['node'] = $form_state['values'];
}

