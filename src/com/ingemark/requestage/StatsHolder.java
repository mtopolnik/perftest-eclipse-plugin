package com.ingemark.requestage;

import java.io.Serializable;
import java.util.List;

public class StatsHolder implements Serializable
{
  final Stats[] statsAry;
  final int scriptsRunning;

  public StatsHolder(List<Stats> statsList, int scriptsRunning) {
    this.statsAry = statsList.toArray(new Stats[statsList.size()]);
    this.scriptsRunning = scriptsRunning;
  }
}
