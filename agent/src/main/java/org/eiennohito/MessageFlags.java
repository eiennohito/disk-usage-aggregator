package org.eiennohito;

/**
 * @author eiennohito
 * @since 2016/02/18
 */
public interface MessageFlags {
  byte ENT_HEADER = 1;
  byte ENT_OVERALL = 2;
  byte ENT_DIRECTORY_DOWN = 3;
  byte ENT_DIRECTORY_UP = 4;
  byte ENT_ERROR = 5;

  /**
   * Entry field separator byte
   */
  byte SEP_FLD = 0;

  /**
   * Message entry separator byte
   */
  byte SEP_ENT = (byte)'\n';
}
