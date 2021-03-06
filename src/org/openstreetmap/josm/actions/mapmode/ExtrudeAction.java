// License: GPL. Copyright 2007 by Immanuel Scholz and others
package org.openstreetmap.josm.actions.mapmode;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.osm.visitor.paint.PaintColors;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Makes a rectangle from a line, or modifies a rectangle.
 */
public class ExtrudeAction extends MapMode implements MapViewPaintable {

    enum Mode { extrude, translate, select, create_new }

    private Mode mode = Mode.select;

    /**
     * If true, when extruding create new node even if segments parallel.
     */
    private boolean alwaysCreateNodes = false;

    private long mouseDownTime = 0;
    private WaySegment selectedSegment = null;
    private Color selectedColor;

    /**
     * drawing settings for helper lines
     */
    private Color helperColor;
    private BasicStroke helperStrokeDash;
    private BasicStroke helperStrokeRA;

    /**
     * Possible directions to move to.
     */
    private List<ReferenceSegment> possibleMoveDirections;

    /**
     * The direction that is currently active.
     */
    private ReferenceSegment activeMoveDirection;

    /**
     * The position of the mouse cursor when the drag action was initiated.
     */
    private Point initialMousePos;
    /**
     * The time which needs to pass between click and release before something
     * counts as a move, in milliseconds
     */
    private int initialMoveDelay = 200;
    /**
     * The initial EastNorths of node1 and node2
     */
    private EastNorth initialN1en;
    private EastNorth initialN2en;
    /**
     * The new EastNorths of node1 and node2
     */
    private EastNorth newN1en;
    private EastNorth newN2en;

    /**
     * the command that performed last move.
     */
    private MoveCommand moveCommand;

    /** The cursor for the 'create_new' mode. */
    private final Cursor cursorCreateNew;

    /** The cursor for the 'translate' mode. */
    private final Cursor cursorTranslate;

    /** The cursor for the 'alwaysCreateNodes' submode. */
    private final Cursor cursorCreateNodes;

    private class ReferenceSegment {
        public final EastNorth en;
        public final WaySegment ws;
        public final boolean perpendicular;

        public ReferenceSegment(EastNorth en, WaySegment ws, boolean perpendicular) {
            this.en = en;
            this.ws = ws;
            this.perpendicular = perpendicular;
        }
    }

    /**
     * This listener is used to indicate the 'create_new' mode, if the Alt modifier is pressed.
     */
    private final AWTEventListener altKeyListener = new AWTEventListener() {
        @Override
        public void eventDispatched(AWTEvent e) {
            if(Main.map == null || Main.map.mapView == null || !Main.map.mapView.isActiveLayerDrawable())
                return;
            InputEvent ie = (InputEvent) e;
            boolean alt = (ie.getModifiers() & (ActionEvent.ALT_MASK|InputEvent.ALT_GRAPH_MASK)) != 0;
            boolean ctrl = (ie.getModifiers() & (ActionEvent.CTRL_MASK)) != 0;
            boolean shift = (ie.getModifiers() & (ActionEvent.SHIFT_MASK)) != 0;
            if (mode == Mode.select) {
                Main.map.mapView.setNewCursor(ctrl ? cursorTranslate : alt ? cursorCreateNew : shift ? cursorCreateNodes : cursor, this);
            }
        }
    };

    /**
     * Create a new SelectAction
     * @param mapFrame The MapFrame this action belongs to.
     */
    public ExtrudeAction(MapFrame mapFrame) {
        super(tr("Extrude"), "extrude/extrude", tr("Create areas"),
                Shortcut.registerShortcut("mapmode:extrude", tr("Mode: {0}", tr("Extrude")), KeyEvent.VK_X, Shortcut.DIRECT),
                mapFrame,
                ImageProvider.getCursor("normal", "rectangle"));
        putValue("help", ht("/Action/Extrude"));
        initialMoveDelay = Main.pref.getInteger("edit.initial-move-delay",200);
        selectedColor = PaintColors.SELECTED.get();
        cursorCreateNew = ImageProvider.getCursor("normal", "rectangle_plus");
        cursorTranslate = ImageProvider.getCursor("normal", "rectangle_move");
        cursorCreateNodes = ImageProvider.getCursor("normal", "rectangle_plussmall");
        helperColor = Main.pref.getColor(marktr("Extrude: helper line"), Color.ORANGE);
        float dash1[] = { 4.0f };
        helperStrokeDash = new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10.0f, dash1, 0.0f);
        helperStrokeRA = new BasicStroke(1);
    }

    @Override public String getModeHelpText() {
        if (mode == Mode.translate)
            return tr("Move a segment along its normal, then release the mouse button.");
        else if (mode == Mode.extrude)
            return tr("Draw a rectangle of the desired size, then release the mouse button.");
        else if (mode == Mode.create_new)
            return tr("Draw a rectangle of the desired size, then release the mouse button.");
        else
            return tr("Drag a way segment to make a rectangle. Ctrl-drag to move a segment along its normal, " +
            "Alt-drag to create a new rectangle, double click to add a new node.");
    }

    @Override public boolean layerIsSupported(Layer l) {
        return l instanceof OsmDataLayer;
    }

    @Override public void enterMode() {
        super.enterMode();
        Main.map.mapView.addMouseListener(this);
        Main.map.mapView.addMouseMotionListener(this);
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(altKeyListener, AWTEvent.KEY_EVENT_MASK);
        } catch (SecurityException ex) {
        }
    }

    @Override public void exitMode() {
        Main.map.mapView.removeMouseListener(this);
        Main.map.mapView.removeMouseMotionListener(this);
        Main.map.mapView.removeTemporaryLayer(this);
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(altKeyListener);
        } catch (SecurityException ex) {
        }
        super.exitMode();
    }

    /**
     * If the left mouse button is pressed over a segment, switch
     * to either extrude, translate or create_new mode depending on whether Ctrl or Alt is held.
     */
    @Override public void mousePressed(MouseEvent e) {
        if(!Main.map.mapView.isActiveLayerVisible())
            return;
        if (!(Boolean)this.getValue("active"))
            return;
        if (e.getButton() != MouseEvent.BUTTON1)
            return;

        updateKeyModifiers(e);

        selectedSegment = Main.map.mapView.getNearestWaySegment(e.getPoint(), OsmPrimitive.isSelectablePredicate);

        if (selectedSegment == null) {
            // If nothing gets caught, stay in select mode
        } else {
            // Otherwise switch to another mode

            if (ctrl) {
                mode = Mode.translate;
            } else if (alt) {
                mode = Mode.create_new;
                // create a new segment and then select and extrude the new segment
                getCurrentDataSet().setSelected(selectedSegment.way);
                alwaysCreateNodes = true;
            } else {
                mode = Mode.extrude;
                getCurrentDataSet().setSelected(selectedSegment.way);
                alwaysCreateNodes = shift;
            }

            // remember initial positions for segment nodes.
            initialN1en = selectedSegment.getFirstNode().getEastNorth();
            initialN2en = selectedSegment.getSecondNode().getEastNorth();

            //gather possible move directions - perpendicular to the selected segment and parallel to neighbor segments
            possibleMoveDirections = new ArrayList<ReferenceSegment>();
            possibleMoveDirections.add(new ReferenceSegment(new EastNorth(
                    initialN1en.getY() - initialN2en.getY(),
                    initialN2en.getX() - initialN1en.getX()
                    ), selectedSegment, true));

            //add directions parallel to neighbor segments

            Node prevNode = getPreviousNode(selectedSegment.lowerIndex);
            if (prevNode != null) {
                EastNorth en = prevNode.getEastNorth();
                possibleMoveDirections.add(new ReferenceSegment(new EastNorth(
                        initialN1en.getX() - en.getX(),
                        initialN1en.getY() - en.getY()
                        ), new WaySegment(selectedSegment.way, getPreviousNodeIndex(selectedSegment.lowerIndex)), false));
            }

            Node nextNode = getNextNode(selectedSegment.lowerIndex + 1);
            if (nextNode != null) {
                EastNorth en = nextNode.getEastNorth();
                possibleMoveDirections.add(new ReferenceSegment(new EastNorth(
                        initialN2en.getX() - en.getX(),
                        initialN2en.getY() - en.getY()
                        ), new WaySegment(selectedSegment.way, getPreviousNodeIndex(getNextNodeIndex(getNextNodeIndex(selectedSegment.lowerIndex)))), false));
            }

            // Signifies that nothing has happened yet
            newN1en = null;
            newN2en = null;
            moveCommand = null;

            Main.map.mapView.addTemporaryLayer(this);

            updateStatusLine();
            Main.map.mapView.repaint();

            // Make note of time pressed
            mouseDownTime = System.currentTimeMillis();

            // Make note of mouse position
            initialMousePos = e.getPoint();
        }
    }

    /**
     * Perform action depending on what mode we're in.
     */
    @Override public void mouseDragged(MouseEvent e) {
        if(!Main.map.mapView.isActiveLayerVisible())
            return;

        // do not count anything as a drag if it lasts less than 100 milliseconds.
        if (System.currentTimeMillis() - mouseDownTime < initialMoveDelay)
            return;

        if (mode == Mode.select) {
            // Just sit tight and wait for mouse to be released.
        } else {
            //move, create new and extrude mode - move the selected segment

            EastNorth initialMouseEn = Main.map.mapView.getEastNorth(initialMousePos.x, initialMousePos.y);
            EastNorth mouseEn = Main.map.mapView.getEastNorth(e.getPoint().x, e.getPoint().y);
            EastNorth mouseMovement = new EastNorth(mouseEn.getX() - initialMouseEn.getX(), mouseEn.getY() - initialMouseEn.getY());

            double bestDistance = Double.POSITIVE_INFINITY;
            EastNorth bestMovement = null;
            activeMoveDirection = null;

            //find the best movement direction and vector
            for (ReferenceSegment direction : possibleMoveDirections) {
                EastNorth movement = calculateSegmentOffset(initialN1en, initialN2en, direction.en, mouseEn);
                if (movement == null) {
                    //if direction parallel to segment.
                    continue;
                }

                double distanceFromMouseMovement = movement.distance(mouseMovement);
                if (bestDistance > distanceFromMouseMovement) {
                    bestDistance = distanceFromMouseMovement;
                    activeMoveDirection = direction;
                    bestMovement = movement;
                }
            }

            newN1en = new EastNorth(initialN1en.getX() + bestMovement.getX(), initialN1en.getY() + bestMovement.getY());
            newN2en = new EastNorth(initialN2en.getX() + bestMovement.getX(), initialN2en.getY() + bestMovement.getY());

            // find out the movement distance, in metres
            double distance = Main.getProjection().eastNorth2latlon(initialN1en).greatCircleDistance(Main.getProjection().eastNorth2latlon(newN1en));
            Main.map.statusLine.setDist(distance);
            updateStatusLine();

            Main.map.mapView.setNewCursor(Cursor.MOVE_CURSOR, this);

            if (mode == Mode.extrude || mode == Mode.create_new) {
                //nothing here
            } else if (mode == Mode.translate) {
                //move nodes to new position
                if (moveCommand == null) {
                    //make a new move command
                    Collection<OsmPrimitive> nodelist = new LinkedList<OsmPrimitive>();
                    nodelist.add(selectedSegment.getFirstNode());
                    nodelist.add(selectedSegment.getSecondNode());
                    moveCommand = new MoveCommand(nodelist, bestMovement.getX(), bestMovement.getY());
                    Main.main.undoRedo.add(moveCommand);
                } else {
                    //reuse existing move command
                    moveCommand.moveAgainTo(bestMovement.getX(), bestMovement.getY());
                }
            }

            Main.map.mapView.repaint();
        }
    }

    /**
     * Do anything that needs to be done, then switch back to select mode
     */
    @Override public void mouseReleased(MouseEvent e) {

        if(!Main.map.mapView.isActiveLayerVisible())
            return;

        if (mode == Mode.select) {
            // Nothing to be done
        } else {
            if (mode == Mode.create_new) {
                if (e.getPoint().distance(initialMousePos) > 10 && newN1en != null) {
                    // crete a new rectangle
                    Collection<Command> cmds = new LinkedList<Command>();
                    Node third = new Node(newN2en);
                    Node fourth = new Node(newN1en);
                    Way wnew = new Way();
                    wnew.addNode(selectedSegment.getFirstNode());
                    wnew.addNode(selectedSegment.getSecondNode());
                    wnew.addNode(third);
                    wnew.addNode(fourth);
                    // ... and close the way
                    wnew.addNode(selectedSegment.getFirstNode());
                    // undo support
                    cmds.add(new AddCommand(third));
                    cmds.add(new AddCommand(fourth));
                    cmds.add(new AddCommand(wnew));
                    Command c = new SequenceCommand(tr("Extrude Way"), cmds);
                    Main.main.undoRedo.add(c);
                    getCurrentDataSet().setSelected(wnew);
                }
            } else if (mode == Mode.extrude) {
                if( e.getClickCount() == 2 && e.getPoint().equals(initialMousePos) ) {
                    // double click add a new node
                    // Should maybe do the same as in DrawAction and fetch all nearby segments?
                    WaySegment ws = Main.map.mapView.getNearestWaySegment(e.getPoint(), OsmPrimitive.isSelectablePredicate);
                    if (ws != null) {
                        Node n = new Node(Main.map.mapView.getLatLon(e.getX(), e.getY()));
                        EastNorth A = ws.getFirstNode().getEastNorth();
                        EastNorth B = ws.getSecondNode().getEastNorth();
                        n.setEastNorth(Geometry.closestPointToSegment(A, B, n.getEastNorth()));
                        Way wnew = new Way(ws.way);
                        wnew.addNode(ws.lowerIndex+1, n);
                        SequenceCommand cmds = new SequenceCommand(tr("Add a new node to an existing way"),
                                new AddCommand(n), new ChangeCommand(ws.way, wnew));
                        Main.main.undoRedo.add(cmds);
                    }
                }
                else if (e.getPoint().distance(initialMousePos) > 10 && newN1en != null && selectedSegment != null) {
                    // create extrusion

                    Collection<Command> cmds = new LinkedList<Command>();
                    Way wnew = new Way(selectedSegment.way);
                    int insertionPoint = selectedSegment.lowerIndex + 1;

                    //find if the new points overlap existing segments (in case of 90 degree angles)
                    Node prevNode = getPreviousNode(selectedSegment.lowerIndex);
                    boolean nodeOverlapsSegment = prevNode != null && Geometry.segmentsParallel(initialN1en, prevNode.getEastNorth(), initialN1en, newN1en);
                    boolean hasOtherWays = this.hasNodeOtherWays(selectedSegment.getFirstNode(), selectedSegment.way);

                    if (nodeOverlapsSegment && !alwaysCreateNodes && !hasOtherWays) {
                        //move existing node
                        Node n1Old = selectedSegment.getFirstNode();
                        cmds.add(new MoveCommand(n1Old, Main.getProjection().eastNorth2latlon(newN1en)));
                    } else {
                        //introduce new node
                        Node n1New = new Node(Main.getProjection().eastNorth2latlon(newN1en));
                        wnew.addNode(insertionPoint, n1New);
                        insertionPoint ++;
                        cmds.add(new AddCommand(n1New));
                    }

                    //find if the new points overlap existing segments (in case of 90 degree angles)
                    Node nextNode = getNextNode(selectedSegment.lowerIndex + 1);
                    nodeOverlapsSegment = nextNode != null && Geometry.segmentsParallel(initialN2en, nextNode.getEastNorth(), initialN2en, newN2en);
                    hasOtherWays = hasNodeOtherWays(selectedSegment.getSecondNode(), selectedSegment.way);

                    if (nodeOverlapsSegment && !alwaysCreateNodes && !hasOtherWays) {
                        //move existing node
                        Node n2Old = selectedSegment.getSecondNode();
                        cmds.add(new MoveCommand(n2Old, Main.getProjection().eastNorth2latlon(newN2en)));
                    } else {
                        //introduce new node
                        Node n2New = new Node(Main.getProjection().eastNorth2latlon(newN2en));
                        wnew.addNode(insertionPoint, n2New);
                        insertionPoint ++;
                        cmds.add(new AddCommand(n2New));
                    }

                    //the way was a single segment, close the way
                    if (wnew.getNodesCount() == 4) {
                        wnew.addNode(selectedSegment.getFirstNode());
                    }

                    cmds.add(new ChangeCommand(selectedSegment.way, wnew));
                    Command c = new SequenceCommand(tr("Extrude Way"), cmds);
                    Main.main.undoRedo.add(c);
                }
            } else if (mode == Mode.translate) {
                //Commit translate
                //the move command is already committed in mouseDragged
            }

            boolean alt = (e.getModifiers() & (ActionEvent.ALT_MASK|InputEvent.ALT_GRAPH_MASK)) != 0;
            boolean ctrl = (e.getModifiers() & (ActionEvent.CTRL_MASK)) != 0;
            boolean shift = (e.getModifiers() & (ActionEvent.SHIFT_MASK)) != 0;
            // Switch back into select mode
            Main.map.mapView.setNewCursor(ctrl ? cursorTranslate : alt ? cursorCreateNew : shift ? cursorCreateNodes : cursor, this);
            Main.map.mapView.removeTemporaryLayer(this);
            selectedSegment = null;
            moveCommand = null;
            mode = Mode.select;

            updateStatusLine();
            Main.map.mapView.repaint();
        }
    }

    /**
     * This method tests if a node has other ways apart from the given one.
     * @param node
     * @param myWay
     * @return true of node belongs only to myWay, false if there are more ways.
     */
    private boolean hasNodeOtherWays(Node node, Way myWay) {
        for (OsmPrimitive p : node.getReferrers()) {
            if (p instanceof Way && p.isUsable() && p != myWay)
                return true;
        }
        return false;
    }

    /***
     * This method calculates offset amount by witch to move the given segment perpendicularly for it to be in line with mouse position.
     * @param segmentP1
     * @param segmentP2
     * @param targetPos
     * @return offset amount of P1 and P2.
     */
    private static EastNorth calculateSegmentOffset(EastNorth segmentP1, EastNorth segmentP2, EastNorth moveDirection,
            EastNorth targetPos) {
        EastNorth intersectionPoint = Geometry.getLineLineIntersection(segmentP1, segmentP2, targetPos,
                new EastNorth(targetPos.getX() + moveDirection.getX(), targetPos.getY() + moveDirection.getY()));

        if (intersectionPoint == null)
            return null;
        else
            //return distance form base to target position
            return new EastNorth(targetPos.getX() - intersectionPoint.getX(),
                    targetPos.getY() - intersectionPoint.getY());
    }

    /**
     * Gets a node from selected way before given index.
     * @param index  index of current node
     * @return index of previous node or -1 if there are no nodes there.
     */
    private int getPreviousNodeIndex(int index) {
        if (index > 0)
            return index - 1;
        else if (selectedSegment.way.isClosed())
            return selectedSegment.way.getNodesCount() - 2;
        else
            return -1;
    }

    /**
     * Gets a node from selected way before given index.
     * @param index  index of current node
     * @return previous node or null if there are no nodes there.
     */
    private Node getPreviousNode(int index) {
        int indexPrev = getPreviousNodeIndex(index);
        if (indexPrev >= 0)
            return selectedSegment.way.getNode(indexPrev);
        else
            return null;
    }


    /**
     * Gets a node from selected way after given index.
     * @param index index of current node
     * @return index of next node or -1 if there are no nodes there.
     */
    private int getNextNodeIndex(int index) {
        int count = selectedSegment.way.getNodesCount();
        if (index <  count - 1)
            return index + 1;
        else if (selectedSegment.way.isClosed())
            return 1;
        else
            return -1;
    }

    /**
     * Gets a node from selected way after given index.
     * @param index index of current node
     * @return next node or null if there are no nodes there.
     */
    private Node getNextNode(int index) {
        int indexNext = getNextNodeIndex(index);
        if (indexNext >= 0)
            return selectedSegment.way.getNode(indexNext);
        else
            return null;
    }

    public void paint(Graphics2D g, MapView mv, Bounds box) {
        if (mode == Mode.select) {
            // Nothing to do
        } else {
            if (newN1en != null) {
                Graphics2D g2 = g;
                g2.setColor(selectedColor);
                g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                Point p1 = mv.getPoint(initialN1en);
                Point p2 = mv.getPoint(initialN2en);
                Point p3 = mv.getPoint(newN1en);
                Point p4 = mv.getPoint(newN2en);

                double fac = 1.0 / activeMoveDirection.en.distance(0,0);
                // mult by factor to get unit vector.
                EastNorth normalUnitVector = new EastNorth(activeMoveDirection.en.getX() * fac, activeMoveDirection.en.getY() * fac);

                // Check to see if our new N1 is in a positive direction with respect to the normalUnitVector.
                // Even if the x component is zero, we should still be able to discern using +0.0 and -0.0
                if (newN1en != null && ((newN1en.getX() > initialN1en.getX()) != (normalUnitVector.getX() > -0.0))) {
                    // If not, use a sign-flipped version of the normalUnitVector.
                    normalUnitVector = new EastNorth(-normalUnitVector.getX(), -normalUnitVector.getY());
                }

                //HACK: swap Y, because the target pixels are top down, but EastNorth is bottom-up.
                //This is normally done by MapView.getPoint, but it does not work on vectors.
                normalUnitVector.setLocation(normalUnitVector.getX(), -normalUnitVector.getY());

                if (mode == Mode.extrude || mode == Mode.create_new) {
                    // Draw rectangle around new area.
                    GeneralPath b = new GeneralPath();
                    b.moveTo(p1.x, p1.y); b.lineTo(p3.x, p3.y);
                    b.lineTo(p4.x, p4.y); b.lineTo(p2.x, p2.y);
                    b.lineTo(p1.x, p1.y);
                    g2.draw(b);

                    if (activeMoveDirection != null) {
                        // Draw reference way
                        Point pr1 = mv.getPoint(activeMoveDirection.ws.getFirstNode().getEastNorth());
                        Point pr2 = mv.getPoint(activeMoveDirection.ws.getSecondNode().getEastNorth());
                        b = new GeneralPath();
                        b.moveTo(pr1.x, pr1.y);
                        b.lineTo(pr2.x, pr2.y);
                        g2.setColor(helperColor);
                        g2.setStroke(helperStrokeDash);
                        g2.draw(b);

                        // Draw right angle marker on first node position, only when moving at right angle
                        if (activeMoveDirection.perpendicular) {
                            // mirror RightAngle marker, so it is inside the extrude
                            double headingRefWS = activeMoveDirection.ws.getFirstNode().getEastNorth().heading(activeMoveDirection.ws.getSecondNode().getEastNorth());
                            double headingMoveDir = Math.atan2(normalUnitVector.getY(), normalUnitVector.getX());
                            double headingDiff = headingRefWS - headingMoveDir;
                            if (headingDiff < 0) headingDiff += 2 * Math.PI;
                            boolean mirrorRA = Math.abs(headingDiff - Math.PI) > 1e-5;

                            // EastNorth units per pixel
                            double factor = 1.0/g2.getTransform().getScaleX();
                            double raoffsetx = 8.0*factor*normalUnitVector.getX();
                            double raoffsety = 8.0*factor*normalUnitVector.getY();

                            Point2D ra1 = new Point2D.Double(pr1.x + raoffsetx, pr1.y+raoffsety);
                            Point2D ra3 = new Point2D.Double(pr1.x - raoffsety*(mirrorRA ? -1 : 1), pr1.y + raoffsetx*(mirrorRA ? -1 : 1));
                            Point2D ra2 = new Point2D.Double(ra1.getX() - raoffsety*(mirrorRA ? -1 : 1), ra1.getY() + raoffsetx*(mirrorRA ? -1 : 1));
                            GeneralPath ra = new GeneralPath();
                            ra.moveTo((float)ra1.getX(), (float)ra1.getY());
                            ra.lineTo((float)ra2.getX(), (float)ra2.getY());
                            ra.lineTo((float)ra3.getX(), (float)ra3.getY());
                            g2.setStroke(helperStrokeRA);
                            g2.draw(ra);
                        }
                    }
                } else if (mode == Mode.translate) {
                    // Highlight the new and old segments.
                    Line2D newline = new Line2D.Double(p3, p4);
                    g2.draw(newline);
                    g2.setStroke(new BasicStroke(1));
                    Line2D oldline = new Line2D.Double(p1, p2);
                    g2.draw(oldline);

                    if (activeMoveDirection != null) {

                        // Draw a guideline along the normal.
                        Line2D normline;
                        Point2D centerpoint = new Point2D.Double((p1.getX()+p2.getX())*0.5, (p1.getY()+p2.getY())*0.5);
                        normline = createSemiInfiniteLine(centerpoint, normalUnitVector, g2);
                        g2.draw(normline);

                        // Draw right angle marker on initial position, only when moving at right angle
                        if (activeMoveDirection.perpendicular) {
                            // EastNorth units per pixel
                            double factor = 1.0/g2.getTransform().getScaleX();

                            double raoffsetx = 8.0*factor*normalUnitVector.getX();
                            double raoffsety = 8.0*factor*normalUnitVector.getY();
                            Point2D ra1 = new Point2D.Double(centerpoint.getX()+raoffsetx, centerpoint.getY()+raoffsety);
                            Point2D ra3 = new Point2D.Double(centerpoint.getX()-raoffsety, centerpoint.getY()+raoffsetx);
                            Point2D ra2 = new Point2D.Double(ra1.getX()-raoffsety, ra1.getY()+raoffsetx);
                            GeneralPath ra = new GeneralPath();
                            ra.moveTo((float)ra1.getX(), (float)ra1.getY());
                            ra.lineTo((float)ra2.getX(), (float)ra2.getY());
                            ra.lineTo((float)ra3.getX(), (float)ra3.getY());
                            g2.draw(ra);
                        }
                    }
                }
            }
        }
    }

    /**
     * Create a new Line that extends off the edge of the viewport in one direction
     * @param start The start point of the line
     * @param unitvector A unit vector denoting the direction of the line
     * @param g the Graphics2D object  it will be used on
     */
    static private Line2D createSemiInfiniteLine(Point2D start, Point2D unitvector, Graphics2D g) {
        Rectangle bounds = g.getDeviceConfiguration().getBounds();
        try {
            AffineTransform invtrans = g.getTransform().createInverse();
            Point2D widthpoint = invtrans.deltaTransform(new Point2D.Double(bounds.width,0), null);
            Point2D heightpoint = invtrans.deltaTransform(new Point2D.Double(0,bounds.height), null);

            // Here we should end up with a gross overestimate of the maximum viewport diagonal in what
            // Graphics2D calls 'user space'. Essentially a manhattan distance of manhattan distances.
            // This can be used as a safe length of line to generate which will always go off-viewport.
            double linelength = Math.abs(widthpoint.getX()) + Math.abs(widthpoint.getY()) + Math.abs(heightpoint.getX()) + Math.abs(heightpoint.getY());

            return new Line2D.Double(start, new Point2D.Double(start.getX() + (unitvector.getX() * linelength) , start.getY() + (unitvector.getY() * linelength)));
        }
        catch (NoninvertibleTransformException e) {
            return new Line2D.Double(start, new Point2D.Double(start.getX() + (unitvector.getX() * 10) , start.getY() + (unitvector.getY() * 10)));
        }
    }
}
