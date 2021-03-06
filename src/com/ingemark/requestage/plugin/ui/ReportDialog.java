package com.ingemark.requestage.plugin.ui;

import static com.ingemark.requestage.Util.gridData;
import static com.ingemark.requestage.plugin.RequestAgePlugin.okButton;
import static com.ingemark.requestage.plugin.RequestAgePlugin.threeDigitFormat;
import static com.ingemark.requestage.plugin.ui.RequestAgeView.requestAgeView;
import static java.text.DateFormat.MEDIUM;
import static java.text.DateFormat.getDateTimeInstance;
import static org.eclipse.swt.SWT.FILL;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.ingemark.requestage.Stats;

public class ReportDialog
{
  private static final String[] headers = {
    "name","req rate","pending","succ rate","fail rate","resp time","resp stdev","resp size",
    "bandwidth"};
  private static final String format; static {
    final StringBuilder b = new StringBuilder();
    for (int i = 0; i < 6; i++) b.append("%s\t");
    format = b.append("\n").toString();
  }
  private static final DateFormat dateTimeFormat = getDateTimeInstance(MEDIUM, MEDIUM);

  static void show(String testName, List<Stats> statsList) {
    final Display disp = Display.getCurrent();
    final Shell top = new Shell(disp);
    final Rectangle bounds = disp.map(requestAgeView.statsParent, null,
        requestAgeView.statsParent.getBounds());
    top.setBounds(bounds);
    top.setLayout(new GridLayout(1, false));
    top.setText(testName + " - RequestAge Report");
    final Label lDateTime = new Label(top, SWT.NONE);
    gridData().align(FILL, FILL).applyTo(lDateTime);
    lDateTime.setText(dateTimeFormat.format(new Date()));
    final Table t = new Table(top, SWT.H_SCROLL | SWT.V_SCROLL);
    gridData().align(FILL, FILL).grab(true, true).applyTo(t);
    t.setLinesVisible(true);
    t.setHeaderVisible(true);
    okButton(top, true);
    for (String h: headers) {
      final TableColumn col = new TableColumn(t, SWT.NONE);
      col.setText(h);
      col.setAlignment(SWT.RIGHT);
    }
    int rpsTotal = 0, pendingTotal = 0, succTotal = 0, failTotal = 0, bytePerSecTotal = 0;
    for (Stats stats : statsList) {
      final float bytePerSec = stats.avgRespSize * (stats.succRespPerSec+stats.failsPerSec);
      final TableItem it = new TableItem(t, SWT.NONE);
      int i = 0;
      it.setText(i++, stats.name);
      it.setText(i++, ""+stats.reqsPerSec);
      it.setText(i++, ""+stats.pendingReqs);
      it.setText(i++, ""+stats.succRespPerSec);
      it.setText(i++, ""+stats.failsPerSec);
      it.setText(i++, threeDigitFormat(stats.avgRespTime)+"s");
      it.setText(i++, threeDigitFormat(stats.stdevRespTime)+"s");
      it.setText(i++, threeDigitFormat(stats.avgRespSize)+"B");
      it.setText(i++, threeDigitFormat(bytePerSec)+"B/s");
      rpsTotal += stats.reqsPerSec;
      pendingTotal += stats.pendingReqs;
      succTotal += stats.succRespPerSec;
      failTotal += stats.failsPerSec;
      bytePerSecTotal += bytePerSec;
    }
    {
      final TableItem total = new TableItem(t, SWT.NONE);
      int i = 0;
      total.setText(i++, "TOTAL");
      total.setText(i++, ""+rpsTotal);
      total.setText(i++, ""+pendingTotal);
      total.setText(i++, ""+succTotal);
      total.setText(i++, ""+failTotal);
      total.setText(i++, "");
      total.setText(i++, "");
      total.setText(i++, "");
      total.setText(i++, ""+threeDigitFormat(bytePerSecTotal)+"B/s");
    }
    for (int i = 0; i < headers.length; i++) t.getColumn(i).pack();

    top.pack();
    top.setVisible(true);
    top.setFocus();
  }

  private static Object[] reportRow(Stats stats) {
    return new Object[] {stats.name, stats.reqsPerSec, stats.succRespPerSec,
        stats.failsPerSec, stats.avgRespTime, stats.stdevRespTime};
  }

  static String textReport(String testName, List<Stats> statsList) {
    final StringWriter w = new StringWriter();
    final PrintWriter pw = new PrintWriter(w);
    pw.format("RequestAge Report for %s on %s\n", testName, dateTimeFormat.format(new Date()));
    String sep = "";
    for (String h : headers) { pw.append(sep).append(h.replace(' ','_')); sep = "\t"; }
    pw.println();
    for (Stats stats : statsList) pw.format(format, reportRow(stats));
    return pw.toString();
  }
}
