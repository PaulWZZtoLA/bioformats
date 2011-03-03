//
// ImageViewer.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.gui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ProgressMonitor;
import javax.swing.SwingConstants;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.xml.parsers.ParserConfigurationException;

import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.ChannelMerger;
import loci.formats.FileStitcher;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatHandler;
import loci.formats.IFormatReader;
import loci.formats.IFormatWriter;
import loci.formats.ImageWriter;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.services.OMEXMLService;

import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.EnumerationException;
import ome.xml.model.enums.PixelType;
import ome.xml.model.enums.handlers.DimensionOrderEnumHandler;
import ome.xml.model.enums.handlers.PixelTypeEnumHandler;
import ome.xml.model.primitives.PositiveInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * ImageViewer is a simple viewer/converter
 * for the Bio-Formats image formats.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/gui/ImageViewer.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/gui/ImageViewer.java;hb=HEAD">Gitweb</a></dd></dl>
 *
 * @author Curtis Rueden ctrueden at wisc.edu
 */
public class ImageViewer extends JFrame implements ActionListener,
  ChangeListener, KeyListener, MouseMotionListener, Runnable, WindowListener
{

  // -- Constants --

  private static final Logger LOGGER =
    LoggerFactory.getLogger(ImageViewer.class);

  protected static final String TITLE = "Bio-Formats Viewer";
  protected static final char ANIMATION_KEY = ' ';

  // -- Fields --

  protected JPanel pane;
  protected ImageIcon icon;
  protected JLabel iconLabel;
  protected JPanel sliderPanel;
  protected JSlider nSlider, zSlider, tSlider, cSlider;
  protected JLabel probeLabel;
  protected JMenuItem fileView, fileSave;

  /** Attached OME metadata store, if available. */
  protected MetadataStore omeMeta;

  /** Current format reader. */
  protected BufferedImageReader myReader;

  /** Current format writer. */
  protected BufferedImageWriter myWriter;

  /** Reader for files on disk. */
  protected IFormatReader fileReader;

  /** Writer for files on disk. */
  protected IFormatWriter fileWriter;

  protected String filename;
  protected IFormatReader in;
  protected BufferedImage[] images;
  protected int sizeZ, sizeT, sizeC;

  protected boolean anim = false;
  protected int fps = 10;

  // -- Fields - OME-XML --

  /** Service for working with OME-XML metadata. */
  protected OMEXMLService omexmlService;

  // -- Constructor --

  /** Constructs an image viewer. */
  public ImageViewer() {
    super(TITLE);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    addWindowListener(this);

    // image I/O engine
    fileReader = new ChannelMerger(new FileStitcher());
    myReader = new BufferedImageReader(fileReader);
    fileWriter = new ImageWriter();
    myWriter = new BufferedImageWriter(fileWriter);

    try {
      // NB: avoid dependencies on optional ome.xml package
      ServiceFactory factory = new ServiceFactory();
      omexmlService = factory.getInstance(OMEXMLService.class);
    }
    catch (DependencyException exc) {
      LOGGER.debug("OME-XML service unavailable", exc);
    }

    // content pane
    pane = new JPanel();
    pane.setLayout(new BorderLayout());
    setContentPane(pane);
    setSize(350, 350); // default size

    // navigation sliders
    sliderPanel = new JPanel();
    sliderPanel.setVisible(false);
    sliderPanel.setBorder(new EmptyBorder(5, 3, 5, 3));
    sliderPanel.setLayout(new BoxLayout(sliderPanel, BoxLayout.Y_AXIS));
    pane.add(BorderLayout.SOUTH, sliderPanel);

    JPanel nPanel = new JPanel();
    nPanel.setLayout(new BoxLayout(nPanel, BoxLayout.X_AXIS));
    sliderPanel.add(nPanel);
    sliderPanel.add(Box.createVerticalStrut(2));

    nSlider = new JSlider(1, 1);
    nSlider.setEnabled(false);
    nSlider.addChangeListener(this);
    nPanel.add(new JLabel("N"));
    nPanel.add(Box.createHorizontalStrut(3));
    nPanel.add(nSlider);

    JPanel ztcPanel = new JPanel();
    ztcPanel.setLayout(new BoxLayout(ztcPanel, BoxLayout.X_AXIS));
    sliderPanel.add(ztcPanel);

    zSlider = new JSlider(1, 1);
    Dimension dim = zSlider.getPreferredSize();
    dim.width = 50;
    zSlider.setPreferredSize(dim);
    zSlider.setEnabled(false);
    zSlider.addChangeListener(this);
    ztcPanel.add(new JLabel("Z"));
    ztcPanel.add(Box.createHorizontalStrut(3));
    ztcPanel.add(zSlider);
    ztcPanel.add(Box.createHorizontalStrut(7));

    tSlider = new JSlider(1, 1);
    tSlider.setPreferredSize(dim);
    tSlider.setEnabled(false);
    tSlider.addChangeListener(this);
    ztcPanel.add(new JLabel("T"));
    ztcPanel.add(Box.createHorizontalStrut(3));
    ztcPanel.add(tSlider);
    ztcPanel.add(Box.createHorizontalStrut(7));

    cSlider = new JSlider(1, 1);
    cSlider.setPreferredSize(dim);
    cSlider.setEnabled(false);
    cSlider.addChangeListener(this);
    ztcPanel.add(new JLabel("C"));
    ztcPanel.add(Box.createHorizontalStrut(3));
    ztcPanel.add(cSlider);
    ztcPanel.add(Box.createHorizontalStrut(7));

    // image icon
    BufferedImage dummy = AWTImageTools.makeImage(new byte[1][1], 1, 1, false);
    icon = new ImageIcon(dummy);
    iconLabel = new JLabel(icon, SwingConstants.LEFT);
    iconLabel.setVerticalAlignment(SwingConstants.TOP);
    pane.add(new JScrollPane(iconLabel));

    // cursor probe
    probeLabel = new JLabel(" ");
    probeLabel.setHorizontalAlignment(SwingConstants.CENTER);
    probeLabel.setBorder(new BevelBorder(BevelBorder.RAISED));
    pane.add(BorderLayout.NORTH, probeLabel);
    iconLabel.addMouseMotionListener(this);

    // menu bar
    JMenuBar menubar = new JMenuBar();
    setJMenuBar(menubar);

    JMenu file = new JMenu("File");
    file.setMnemonic('f');
    menubar.add(file);
    JMenuItem fileOpen = new JMenuItem("Open...");
    fileOpen.setMnemonic('o');
    fileOpen.setActionCommand("open");
    fileOpen.addActionListener(this);
    file.add(fileOpen);
    fileSave = new JMenuItem("Save...");
    fileSave.setMnemonic('s');
    fileSave.setEnabled(false);
    fileSave.setActionCommand("save");
    fileSave.addActionListener(this);
    file.add(fileSave);
    if (omexmlService != null) {
      fileView = new JMenuItem("View Metadata...");
      fileView.setMnemonic('m');
      fileView.setEnabled(true);
      fileView.setActionCommand("view");
      fileView.addActionListener(this);
      file.add(fileView);
    }
    JMenuItem fileExit = new JMenuItem("Exit");
    fileExit.setMnemonic('x');
    fileExit.setActionCommand("exit");
    fileExit.addActionListener(this);
    file.add(fileExit);

    JMenu options = new JMenu("Options");
    options.setMnemonic('p');
    menubar.add(options);
    JMenuItem optionsFPS = new JMenuItem("Frames per Second...");
    optionsFPS.setMnemonic('f');
    optionsFPS.setActionCommand("fps");
    optionsFPS.addActionListener(this);
    options.add(optionsFPS);

    JMenu help = new JMenu("Help");
    help.setMnemonic('h');
    menubar.add(help);
    JMenuItem helpAbout = new JMenuItem("About...");
    helpAbout.setMnemonic('a');
    helpAbout.setActionCommand("about");
    helpAbout.addActionListener(this);
    help.add(helpAbout);

    // add key listener to focusable components
    nSlider.addKeyListener(this);
    zSlider.addKeyListener(this);
    tSlider.addKeyListener(this);
    cSlider.addKeyListener(this);
  }

  /** Opens the given data source using the current format reader. */
  public void open(String id) {
    wait(true);
    try {
      //Location f = new Location(id);
      //id = f.getAbsolutePath();
      IMetadata meta = null;
      if (omexmlService != null) {
        try {
          meta = omexmlService.createOMEXMLMetadata();
          myReader.setMetadataStore(meta);
        }
        catch (ServiceException exc) {
          LOGGER.debug("Could not create OME-XML metadata", exc);
        }
      }
      if (meta == null) {
        LOGGER.info("OME metadata unavailable");
      }
      myReader.setId(id);
      int num = myReader.getImageCount();
      ProgressMonitor progress = new ProgressMonitor(this,
        "Reading " + id, null, 0, num + 1);
      sizeZ = myReader.getSizeZ();
      sizeT = myReader.getSizeT();
      sizeC = myReader.getEffectiveSizeC();
      //if (myReader.isRGB(id)) sizeC = (sizeC + 2) / 3; // adjust for RGB
      progress.setProgress(1);
      BufferedImage[] img = new BufferedImage[num];
      for (int i=0; i<num; i++) {
        if (progress.isCanceled()) break;
        img[i] = myReader.openImage(i);
        if (i == 0) setImages(myReader, img);
        progress.setProgress(i + 2);
      }
      myReader.close(true);
    }
    catch (FormatException exc) {
      LOGGER.info("", exc);
      wait(false);
      return;
    }
    catch (IOException exc) {
      LOGGER.info("", exc);
      wait(false);
      return;
    }
    wait(false);
  }

  /**
   * Saves the current images to the given destination
   * using the current format writer.
   */
  public void save(String id) {
    if (images == null) return;
    wait(true);
    try {
      if (omeMeta == null) {
        omeMeta = omexmlService.createOMEXMLMetadata();
        omeMeta.setImageID(MetadataTools.createLSID("Image", 0), 0);
        omeMeta.setPixelsID(MetadataTools.createLSID("Pixels", 0), 0);
        omeMeta.setPixelsBinDataBigEndian(false, 0, 0);

        String order = "XYCZT";
        if (in != null) order = in.getDimensionOrder();

        omeMeta.setPixelsDimensionOrder((DimensionOrder)
          new DimensionOrderEnumHandler().getEnumeration(order), 0);
        int type = AWTImageTools.getPixelType(images[0]);
        String pixelType = FormatTools.getPixelTypeString(type);
        omeMeta.setPixelsType(
          (PixelType) new PixelTypeEnumHandler().getEnumeration(pixelType), 0);

        int rgbChannelCount = images[0].getRaster().getNumBands();
        int realChannelCount = sizeC / rgbChannelCount;

        for (int i=0; i<realChannelCount; i++) {
          omeMeta.setChannelID(MetadataTools.createLSID("Channel", i, 0), 0, i);
          omeMeta.setChannelSamplesPerPixel(
            new PositiveInteger(rgbChannelCount), 0, i);
        }

        omeMeta.setPixelsSizeX(new PositiveInteger(images[0].getWidth()), 0);
        omeMeta.setPixelsSizeY(new PositiveInteger(images[0].getHeight()), 0);
        omeMeta.setPixelsSizeC(new PositiveInteger(sizeC), 0);
        omeMeta.setPixelsSizeZ(new PositiveInteger(sizeZ), 0);
        omeMeta.setPixelsSizeT(new PositiveInteger(sizeT), 0);
      }
      myWriter.setMetadataRetrieve(omexmlService.asRetrieve(omeMeta));
      myWriter.setId(id);
      boolean stack = myWriter.canDoStacks();
      ProgressMonitor progress = new ProgressMonitor(this,
        "Saving " + id, null, 0, stack ? images.length : 1);
      if (stack) {
        // save entire stack
        for (int i=0; i<images.length; i++) {
          progress.setProgress(i);
          boolean canceled = progress.isCanceled();
          myWriter.saveImage(i, images[i]);
          if (canceled) break;
        }
        progress.setProgress(images.length);
      }
      else {
        // save current image only
        myWriter.savePlane(0, getImage());
        progress.setProgress(1);
      }
      myWriter.close();
    }
    catch (FormatException exc) { LOGGER.info("", exc); }
    catch (IOException exc) { LOGGER.info("", exc); }
    catch (ServiceException exc) { LOGGER.info("", exc); }
    catch (EnumerationException exc) { LOGGER.info("", exc); }
    wait(false);
  }

  /** Sets the viewer to display the given images. */
  public void setImages(BufferedImage[] img) { setImages(null, img); }

  /**
   * Sets the viewer to display the given images, obtaining
   * corresponding core metadata from the specified format reader.
   */
  public void setImages(IFormatReader reader, BufferedImage[] img) {
    filename = reader == null ? null : reader.getCurrentFile();
    in = reader;
    images = img;

    if (reader == null) sizeZ = sizeC = sizeT = 1;
    else {
      sizeZ = reader.getSizeZ();
      sizeT = reader.getSizeT();
      sizeC = reader.getEffectiveSizeC();
      omeMeta = reader.getMetadataStore();
      if (omexmlService == null || !omexmlService.isOMEXMLMetadata(omeMeta)) {
        omeMeta = null;
      }
    }
    fileView.setEnabled(omeMeta != null);

    fileSave.setEnabled(true);
    nSlider.removeChangeListener(this);
    zSlider.removeChangeListener(this);
    tSlider.removeChangeListener(this);
    cSlider.removeChangeListener(this);
    nSlider.setValue(1);
    nSlider.setMaximum(images.length);
    nSlider.setEnabled(images.length > 1);
    zSlider.setValue(1);
    zSlider.setMaximum(sizeZ);
    zSlider.setEnabled(sizeZ > 1);
    tSlider.setValue(1);
    tSlider.setMaximum(sizeT);
    tSlider.setEnabled(sizeT > 1);
    cSlider.setValue(1);
    cSlider.setMaximum(sizeC);
    cSlider.setEnabled(sizeC > 1);
    nSlider.addChangeListener(this);
    zSlider.addChangeListener(this);
    tSlider.addChangeListener(this);
    cSlider.addChangeListener(this);
    sliderPanel.setVisible(images.length > 1);

    updateLabel(-1, -1);
    sb.setLength(0);
    if (filename != null) {
      sb.append(reader.getCurrentFile());
      sb.append(" ");
    }
    String format = reader == null ? null : reader.getFormat();
    if (format != null) {
      sb.append("(");
      sb.append(format);
      sb.append(")");
      sb.append(" ");
    }
    if (filename != null || format != null) sb.append("- ");
    sb.append(TITLE);
    setTitle(sb.toString());
    if (images != null) icon.setImage(images[0]);
    pack();
  }

  /** Gets the currently displayed image. */
  public BufferedImage getImage() {
    int ndx = getImageIndex();
    return images == null || ndx >= images.length ? null : images[ndx];
  }

  /** Gets the index of the currently displayed image. */
  public int getImageIndex() { return nSlider.getValue() - 1; }

  /** Gets the Z value of the currently displayed image. */
  public int getZ() { return zSlider.getValue() - 1; }

  /** Gets the T value of the currently displayed image. */
  public int getT() { return tSlider.getValue() - 1; }

  /** Gets the C value of the currently displayed image. */
  public int getC() { return cSlider.getValue() - 1; }

  // -- Window API methods --
  public void setVisible(boolean visible) {
    super.setVisible(visible);
    // kick off animation thread
    new Thread(this).start();
  }

  // -- ActionListener API methods --

  /** Handles menu commands. */
  public void actionPerformed(ActionEvent e) {
    String cmd = e.getActionCommand();
    if ("open".equals(cmd)) {
      wait(true);
      JFileChooser chooser = GUITools.buildFileChooser(myReader);
      wait(false);
      int rval = chooser.showOpenDialog(this);
      if (rval == JFileChooser.APPROVE_OPTION) {
        final File file = chooser.getSelectedFile();
        if (file != null) open(file.getAbsolutePath(), fileReader);
      }
    }
    else if ("save".equals(cmd)) {
      wait(true);
      JFileChooser chooser = GUITools.buildFileChooser(myWriter);
      wait(false);
      int rval = chooser.showSaveDialog(this);
      if (rval == JFileChooser.APPROVE_OPTION) {
        final File file = chooser.getSelectedFile();
        if (file != null) save(file.getAbsolutePath(), fileWriter);
      }
    }
    else if ("view".equals(cmd)) {
      if (omeMeta != null) {
        XMLWindow metaWindow = new XMLWindow("OME Metadata - " + getTitle());
        metaWindow.setDefaultCloseOperation(XMLWindow.DISPOSE_ON_CLOSE);
        Exception exception = null;
        try {
          MetadataRetrieve retrieve = omexmlService.asRetrieve(omeMeta);
          metaWindow.setXML(omexmlService.getOMEXML(retrieve));
          metaWindow.setVisible(true);
        }
        catch (ServiceException exc) { exception = exc; }
        catch (ParserConfigurationException exc) { exception = exc; }
        catch (SAXException exc) { exception = exc; }
        catch (IOException exc) { exception = exc; }
        if (exception != null) {
          LOGGER.info("Cannot display OME metadata", exception);
        }
      }
    }
    else if ("exit".equals(cmd)) dispose();
    else if ("fps".equals(cmd)) {
      // HACK - JOptionPane prevents shutdown on dispose
      setDefaultCloseOperation(EXIT_ON_CLOSE);

      String result = JOptionPane.showInputDialog(this,
        "Animate using space bar. How many frames per second?", "" + fps);
      try {
        fps = Integer.parseInt(result);
      }
      catch (NumberFormatException exc) {
        LOGGER.debug("Could not parse fps '{}'", fps, exc);
      }
    }
    else if ("about".equals(cmd)) {
      // HACK - JOptionPane prevents shutdown on dispose
      setDefaultCloseOperation(EXIT_ON_CLOSE);

      String msg = "<html>" +
        "OME Bio-Formats package for reading and " +
        "converting biological file formats." +
        "<br>Copyright (C) 2005-@year@ " +
        "UW-Madison LOCI and Glencoe Software, Inc." +
        "<br><i>" + FormatTools.URL_BIO_FORMATS + "</i>" +
        "<br>Revision @vcs.revision@, built @date@";
      ImageIcon bioFormatsLogo = new ImageIcon(
          IFormatHandler.class.getResource("bio-formats-logo.png"));
      JOptionPane.showMessageDialog(null, msg, "Bio-Formats",
          JOptionPane.INFORMATION_MESSAGE, bioFormatsLogo);
    }
  }

  // -- ChangeListener API methods --

  /** Handles slider events. */
  public void stateChanged(ChangeEvent e) {
    Object src = e.getSource();
    boolean outOfBounds = false;
    if (src == nSlider) {
      // update Z, T and C sliders
      int ndx = getImageIndex();
      int[] zct = in == null ? new int[] {-1, -1, -1} : in.getZCTCoords(ndx);
      if (zct[0] >= 0) {
        zSlider.removeChangeListener(this);
        zSlider.setValue(zct[0] + 1);
        zSlider.addChangeListener(this);
      }
      if (zct[1] >= 0) {
        cSlider.removeChangeListener(this);
        cSlider.setValue(zct[1] + 1);
        cSlider.addChangeListener(this);
      }
      if (zct[2] >= 0) {
        tSlider.removeChangeListener(this);
        tSlider.setValue(zct[2] + 1);
        tSlider.addChangeListener(this);
      }
    }
    else {
      // update N slider
      int ndx = in == null ? -1 : in.getIndex(getZ(), getC(), getT());
      if (ndx >= 0) {
        nSlider.removeChangeListener(this);
        outOfBounds = ndx >= nSlider.getMaximum();
        nSlider.setValue(ndx + 1);
        nSlider.addChangeListener(this);
      }
    }
    updateLabel(-1, -1);
    BufferedImage image = outOfBounds ? null : getImage();
    if (image == null) {
      iconLabel.setIcon(null);
      iconLabel.setText("No image plane");
    }
    else {
      icon.setImage(image);
      iconLabel.setIcon(icon);
      iconLabel.setText(null);
    }
  }

  // -- KeyListener API methods --

  /** Handles key presses. */
  public void keyPressed(KeyEvent e) {
    if (e.getKeyChar() == ANIMATION_KEY) anim = !anim; // toggle animation
  }

  public void keyReleased(KeyEvent e) { }
  public void keyTyped(KeyEvent e) { }

  // -- MouseMotionListener API methods --

  /** Handles cursor probes. */
  public void mouseDragged(MouseEvent e) { updateLabel(e.getX(), e.getY()); }

  /** Handles cursor probes. */
  public void mouseMoved(MouseEvent e) { updateLabel(e.getX(), e.getY()); }

  // -- Runnable API methods --

  /** Handles animation. */
  public void run() {
    while (isVisible()) {
      if (anim) {
        int t = tSlider.getValue();
        t = t % tSlider.getMaximum() + 1;
        tSlider.setValue(t);
      }
      try {
        Thread.sleep(1000 / fps);
      }
      catch (InterruptedException exc) {
        LOGGER.debug("", exc);
      }
    }
  }

  // -- WindowListener API methods --

  public void windowClosing(WindowEvent e) { }
  public void windowActivated(WindowEvent e) { }
  public void windowDeactivated(WindowEvent e) { }
  public void windowOpened(WindowEvent e) { }
  public void windowIconified(WindowEvent e) { }
  public void windowDeiconified(WindowEvent e) { }

  public void windowClosed(WindowEvent e) {
    try {
      myReader.close();
      myWriter.close();
      in.close();
    }
    catch (IOException io) { }
  }

  // -- Helper methods --

  protected StringBuffer sb = new StringBuffer();

  /** Updates cursor probe label. */
  protected void updateLabel(int x, int y) {
    if (images == null) return;
    int ndx = getImageIndex();
    sb.setLength(0);
    if (images.length > 1) {
      sb.append("N=");
      sb.append(ndx + 1);
      sb.append("/");
      sb.append(images.length);
    }
    if (sizeZ > 1) {
      sb.append("; Z=");
      sb.append(getZ() + 1);
      sb.append("/");
      sb.append(sizeZ);
    }
    if (sizeT > 1) {
      sb.append("; T=");
      sb.append(getT() + 1);
      sb.append("/");
      sb.append(sizeT);
    }
    if (sizeC > 1) {
      sb.append("; C=");
      sb.append(getC() + 1);
      sb.append("/");
      sb.append(sizeC);
    }
    BufferedImage image = images[ndx];
    int w = image == null ? -1 : image.getWidth();
    int h = image == null ? -1 : image.getHeight();
    if (x >= w) x = w - 1;
    if (y >= h) y = h - 1;
    if (x >= 0 && y >= 0) {
      if (images.length > 1) sb.append("; ");
      sb.append("X=");
      sb.append(x);
      if (w > 0) {
        sb.append("/");
        sb.append(w);
      }
      sb.append("; Y=");
      sb.append(y);
      if (h > 0) {
        sb.append("/");
        sb.append(h);
      }
      if (image != null) {
        Raster r = image.getRaster();
        double[] pix = r.getPixel(x, y, (double[]) null);
        sb.append("; value");
        sb.append(pix.length > 1 ? "s=(" : "=");
        for (int i=0; i<pix.length; i++) {
          if (i > 0) sb.append(", ");
          sb.append(pix[i]);
        }
        if (pix.length > 1) sb.append(")");
        sb.append("; type=");
        int pixelType = AWTImageTools.getPixelType(image);
        sb.append(FormatTools.getPixelTypeString(pixelType));
      }
    }
    sb.append(" ");
    probeLabel.setText(sb.toString());
  }

  /** Toggles wait cursor. */
  protected void wait(boolean wait) {
    setCursor(wait ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
  }

  /**
   * Opens from the given data source using the specified reader
   * in a separate thread.
   */
  protected void open(final String id, final IFormatReader r) {
    new Thread("ImageViewer-Opener") {
      public void run() {
        try {
          myReader.close();
        }
        catch (IOException exc) { LOGGER.info("", exc); }
        myReader = new BufferedImageReader(r);
        open(id);
      }
    }.start();
  }

  /**
   * Saves to the given data destination using the specified writer
   * in a separate thread.
   */
  protected void save(final String id, final IFormatWriter w) {
    new Thread("ImageViewer-Saver") {
      public void run() {
        try {
          myWriter.close();
        }
        catch (IOException exc) { LOGGER.info("", exc); }
        myWriter = BufferedImageWriter.makeBufferedImageWriter(w);
        save(id);
      }
    }.start();
  }

  // -- Main method --

  public static void main(String[] args) {
    ImageViewer viewer = new ImageViewer();
    viewer.setVisible(true);
    if (args.length > 0) viewer.open(args[0]);
  }

}
