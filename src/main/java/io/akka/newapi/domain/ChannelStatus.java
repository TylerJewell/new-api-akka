package io.akka.newapi.domain;

/** SPEC-001 §2 -- mirrors common.ChannelStatusEnabled/AutoDisabled/ManuallyDisabled. */
public enum ChannelStatus {
  ENABLED,
  AUTO_DISABLED,
  MANUALLY_DISABLED
}
