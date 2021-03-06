package hex.genmodel;

import hex.genmodel.algos.DrfRawModel;
import hex.genmodel.algos.GbmRawModel;
import hex.genmodel.utils.ParseUtils;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


/**
 * Prediction model based on the persisted binary data.
 */
abstract public class RawModel extends GenModel {
    protected ContentReader _reader;
    protected hex.ModelCategory _category;
    protected String _uuid;
    protected boolean _supervised;
    protected int _nfeatures;
    protected int _nclasses;
    protected boolean _balanceClasses;
    protected double _defaultThreshold;
    protected double[] _priorClassDistrib;
    protected double[] _modelClassDistrib;

    /**
     * Primary factory method for constructing RawModel instances.
     *
     * @param file Name of the zip file (or folder) with the model's data. This should be the data retrieved via
     *             the `GET /3/Models/{model_id}/data` endpoint.
     * @return New `RawModel` object.
     * @throws IOException if `file` does not exist, or cannot be read, or does not represent a valid model.
     */
    static public RawModel load(String file) throws IOException {
        File f = new File(file);
        if (!f.exists())
            throw new FileNotFoundException("File " + file + " cannot be found.");
        ContentReader cr = f.isDirectory()? new FolderContentReader(file) : new ArchiveContentReader(file);
        Map<String, Object> info = parseModelInfo(cr);
        String[] columns = (String[]) info.get("[columns]");
        String[][] domains = parseModelDomains(cr, columns.length, info.get("[domains]"));
        String algo = (String) info.get("algorithm");
        if (algo == null)
            throw new IOException("Model file does not contain information about the model's algorithm.");

        // Create and return a subclass instance
        switch (algo) {
            case "Distributed Random Forest":
                return new DrfRawModel(cr, info, columns, domains);
            case "Gradient Boosting Method":
                return new GbmRawModel(cr, info, columns, domains);
            default:
                throw new IOException("Unsupported algorithm " + algo + " in model's info.");
        }
    }

    @Override public String getUUID() { return _uuid; }
    @Override public hex.ModelCategory getModelCategory() { return _category; }
    @Override public boolean isSupervised() { return _supervised; }
    @Override public int nfeatures() { return _nfeatures; }
    @Override public int nclasses() { return _nclasses; }


    //------------------------------------------------------------------------------------------------------------------
    // (Private) initialization
    //------------------------------------------------------------------------------------------------------------------

    protected RawModel(ContentReader cr, Map<String, Object> info, String[] columns, String[][] domains) {
        super(columns, domains);
        _reader = cr;
        _uuid = (String) info.get("uuid");
        _category = hex.ModelCategory.valueOf((String) info.get("category"));
        _supervised = (boolean) info.get("supervised");
        _nfeatures = (int) info.get("n_features");
        _nclasses = (int) info.get("n_classes");
        _balanceClasses = (boolean) info.get("balance_classes");
        _defaultThreshold = (double) info.get("default_threshold");
        _priorClassDistrib = (double[]) info.get("prior_class_distrib");
        _modelClassDistrib = (double[]) info.get("model_class_distrib");
    }

    static private Map<String, Object> parseModelInfo(ContentReader reader) throws IOException {
        BufferedReader br = reader.getTextFile("model.ini");
        Map<String, Object> info = new HashMap<>();
        String line;
        int section = 0;
        int ic = 0;  // Index for `columns` array
        String[] columns = new String[0];  // array of column names, will be initialized later
        Map<Integer, String> domains = new HashMap<>();  // map of (categorical column index => name of the domain file)
        while (true) {
            line = br.readLine();
            if (line == null) break;
            line = line.trim();
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.equals("[info]"))
                section = 1;
            else if (line.equals("[columns]")) {
                section = 2;  // Enter the [columns] section
                Integer n_columns = (Integer) info.get("n_columns");
                if (n_columns == null)
                    throw new IOException("`n_columns` variable is missing in the model info.");
                columns = new String[n_columns];
                info.put("[columns]", columns);
            } else if (line.equals("[domains]")) {
                section = 3; // Enter the [domains] section
                info.put("[domains]", domains);
            } else if (section == 1) {
                // [info] section: just parse key-value pairs and store them into the `info` map.
                String[] res = line.split("\\s*=\\s*", 2);
                info.put(res[0], res[0].equals("uuid")? res[1] : ParseUtils.tryParse(res[1]));
            } else if (section == 2) {
                // [columns] section
                if (ic >= columns.length)
                    throw new IOException("`n_columns` variable is too small.");
                columns[ic++] = line;
            } else if (section == 3) {
                // [domains] section
                String[] res = line.split(":\\s*", 2);
                int col_index = Integer.parseInt(res[0]);
                domains.put(col_index, res[1]);
            }
        }
        return info;
    }

    static private String[][] parseModelDomains(ContentReader reader, int n_columns, Object domains_assignment)
            throws IOException {
        String[][] domains = new String[n_columns][];
        // noinspection unchecked
        Map<Integer, String> domass = (Map<Integer, String>) domains_assignment;
        for (Map.Entry<Integer, String> e : domass.entrySet()) {
            int col_index = e.getKey();
            // There is a file with categories of the response column, but we ignore it.
            if (col_index >= n_columns) continue;
            String[] info = e.getValue().split(" ", 2);
            int n_elements = Integer.parseInt(info[0]);
            String domfile = info[1];
            String[] domain = new String[n_elements];
            BufferedReader br = reader.getTextFile("domains/" + domfile);
            String line;
            int id = 0;  // domain elements counter
            while (true) {
                line = br.readLine();
                if (line == null) break;
                domain[id++] = line;
            }
            if (id != n_elements)
                throw new IOException("Not enough elements in the domain file");
            domains[col_index] = domain;
        }
        return domains;
    }



    //------------------------------------------------------------------------------------------------------------------
    // Utility classes for accessing model's data either from a zip file, or from a directory
    //------------------------------------------------------------------------------------------------------------------

    public interface ContentReader {
        BufferedReader getTextFile(String filename) throws IOException;
        byte[] getBinaryFile(String filename) throws IOException;
    }

    static private class FolderContentReader implements ContentReader {
        private String root;

        public FolderContentReader(String folder) {
            root = folder;
        }

        @Override
        public BufferedReader getTextFile(String filename) throws IOException {
            File f = new File(root, filename);
            FileReader fr = new FileReader(f);
            return new BufferedReader(fr);
        }

        @Override
        public byte[] getBinaryFile(String filename) throws IOException {
            File f = new File(root, filename);
            byte[] out = new byte[(int) f.length()];
            DataInputStream dis = new DataInputStream(new FileInputStream(f));
            dis.readFully(out);
            return out;
        }
    }

    static private class ArchiveContentReader implements ContentReader {
        private ZipFile zf;

        public ArchiveContentReader(String archivename) throws IOException {
            zf = new ZipFile(archivename);
        }

        @Override
        public BufferedReader getTextFile(String filename) throws IOException {
            InputStream input = zf.getInputStream(zf.getEntry(filename));
            return new BufferedReader(new InputStreamReader(input));
        }

        @Override
        public byte[] getBinaryFile(String filename) throws IOException {
            ZipEntry za = zf.getEntry(filename);
            if (za == null)
                throw new IOException("Tree file " + filename + " not found");
            byte[] out = new byte[(int) za.getSize()];
            DataInputStream dis = new DataInputStream(zf.getInputStream(za));
            dis.readFully(out);
            return out;
        }
    }
}
