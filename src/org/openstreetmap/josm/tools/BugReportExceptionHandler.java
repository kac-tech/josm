// License: GPL. Copyright 2007 by Immanuel Scholz and others
package org.openstreetmap.josm.tools;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.awt.GridBagLayout;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.ByteBuffer;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.ShowStatusReportAction;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.JMultilineLabel;
import org.openstreetmap.josm.plugins.PluginHandler;

/**
 * An exception handler that asks the user to send a bug report.
 *
 * @author imi
 */
public final class BugReportExceptionHandler implements Thread.UncaughtExceptionHandler {

    private static boolean handlingInProgress = false;
    private static int exceptionCounter = 0;
    private static boolean suppressExceptionDialogs = false;

    public void uncaughtException(Thread t, Throwable e) {
        handleException(e);
    }

    //http://stuffthathappens.com/blog/2007/10/15/one-more-note-on-uncaught-exception-handlers/
    public void handle(Throwable t) {
        handleException(t);
    }

    public static void handleException(final Throwable e) {
        if (handlingInProgress)
            return;                  // we do not handle secondary exceptions, this gets too messy
        if (suppressExceptionDialogs)
            return;
        handlingInProgress = true;
        exceptionCounter++;
        try {
            e.printStackTrace();
            if (Main.parent != null) {
                if (e instanceof OutOfMemoryError) {
                    // do not translate the string, as translation may raise an exception
                    JOptionPane.showMessageDialog(Main.parent, "JOSM is out of memory. " +
                            "Strange things may happen.\nPlease restart JOSM with the -Xmx###M option,\n" +
                            "where ### is the number of MB assigned to JOSM (e.g. 256).\n" +
                            "Currently, " + Runtime.getRuntime().maxMemory()/1024/1024 + " MB are available to JOSM.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                            );
                    return;
                }


                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        // Give the user a chance to deactivate the plugin which threw the exception (if it
                        // was thrown from a plugin)
                        //
                        PluginHandler.disablePluginAfterException(e);

                        // Then ask for submitting a bug report, for exceptions thrown from a plugin too
                        //
                        ExtendedDialog ed = new ExtendedDialog(Main.parent, tr("Unexpected Exception"), new String[] {tr("Do nothing"), tr("Report Bug")});
                        ed.setIcon(JOptionPane.ERROR_MESSAGE);
                        JPanel pnl = new JPanel(new GridBagLayout());
                        pnl.add(new JLabel(
                                "<html>"
                                        + tr("An unexpected exception occurred.<br>" +
                                                "This is always a coding error. If you are running the latest<br>" +
                                                "version of JOSM, please consider being kind and file a bug report."
                                                )
                                                + "</html>"), GBC.eol());
                        JCheckBox cbSuppress = null;
                        if (exceptionCounter > 1) {
                            cbSuppress = new JCheckBox(tr("Suppress further error dialogs for this session."));
                            pnl.add(cbSuppress, GBC.eol());
                        }
                        ed.setContent(pnl);
                        ed.showDialog();
                        if (cbSuppress != null && cbSuppress.isSelected()) {
                            suppressExceptionDialogs = true;
                        }
                        if (ed.getValue() != 2) return;

                        try {
                            final int maxlen = 6000;
                            StringWriter stack = new StringWriter();
                            e.printStackTrace(new PrintWriter(stack));

                            String text = ShowStatusReportAction.getReportHeader()
                                    + stack.getBuffer().toString();
                            String urltext = text.replaceAll("\r",""); /* strip useless return chars */
                            if(urltext.length() > maxlen)
                            {
                                urltext = urltext.substring(0,maxlen);
                                int idx = urltext.lastIndexOf("\n");
                                /* cut whole line when not loosing too much */
                                if(maxlen-idx < 200) {
                                    urltext = urltext.substring(0,idx+1);
                                }
                                urltext += "...<snip>...\n";
                            }

                            URL url = new URL("http://josm.openstreetmap.de/josmticket?" +
                                    "tdata="+Base64.encode(ByteBuffer.wrap(urltext.getBytes("UTF8")), true));

                            JPanel p = new JPanel(new GridBagLayout());
                            p.add(new JMultilineLabel(
                                    tr("You have encountered an error in JOSM. Before you file a bug report " +
                                            "make sure you have updated to the latest version of JOSM here:")), GBC.eol());
                            p.add(new UrlLabel("http://josm.openstreetmap.de/#Download",2), GBC.eop().insets(8,0,0,0));
                            p.add(new JMultilineLabel(
                                    tr("You should also update your plugins. If neither of those help please " +
                                            "file a bug report in our bugtracker using this link:")), GBC.eol());
                            p.add(new UrlLabel(url.toString(), "http://josm.openstreetmap.de/josmticket?...",2), GBC.eop().insets(8,0,0,0));
                            p.add(new JMultilineLabel(
                                    tr("There the error information provided below should already be " +
                                            "filled in for you. Please include information on how to reproduce " +
                                            "the error and try to supply as much detail as possible.")), GBC.eop());
                            p.add(new JMultilineLabel(
                                    tr("Alternatively, if that does not work you can manually fill in the information " +
                                            "below at this URL:")), GBC.eol());
                            p.add(new UrlLabel("http://josm.openstreetmap.de/newticket",2), GBC.eop().insets(8,0,0,0));
                            if (Utils.copyToClipboard(text)) {
                                p.add(new JLabel(tr("(The text has already been copied to your clipboard.)")), GBC.eop());
                            }

                            JTextArea info = new JTextArea(text, 18, 60);
                            info.setCaretPosition(0);
                            info.setEditable(false);
                            p.add(new JScrollPane(info), GBC.eop());

                            for (Component c: p.getComponents()) {
                                if (c instanceof JMultilineLabel) {
                                    ((JMultilineLabel)c).setMaxWidth(400);
                                }
                            }

                            JOptionPane.showMessageDialog(Main.parent, p, tr("You have encountered a bug in JOSM"), JOptionPane.ERROR_MESSAGE);
                        } catch (Exception e1) {
                            e1.printStackTrace();
                        }
                    }
                });
            }
        } finally {
            handlingInProgress = false;
        }
    }
    public static boolean exceptionHandlingInProgress() {
        return handlingInProgress;
    }
}
