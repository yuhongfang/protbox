package edduarte.protbox.ui.windows;

import edduarte.protbox.core.Constants;
import edduarte.protbox.core.PbxUser;
import edduarte.protbox.core.registry.PReg;
import edduarte.protbox.ui.listeners.OnMouseClick;
import edduarte.protbox.utils.Utils;
import edduarte.protbox.utils.tuples.Pair;
import edduarte.protbox.utils.tuples.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;

/**
 * @author Eduardo Duarte (<a href="mailto:emod@ua.pt">emod@ua.pt</a>)
 * @version 2.0
 */
public class UserValidationWindow extends JFrame {

    private static final Logger logger = LoggerFactory.getLogger(UserValidationWindow.class);

    private static final Map<PReg, UserValidationWindow> instances = new HashMap<>();

    private UserValidationWindow(final String registryAlgorithm, final SecretKey registryKey,
                                 final String sharedFolderName, final File askFile) {
        super("A new user asked your permission to access the folder " + sharedFolderName + " - Protbox");
        setIconImage(Constants.getAsset("box.png"));
        setLayout(null);

        final File parent = askFile.getParentFile();
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(askFile))) {
            final Triple<PbxUser, byte[], byte[]> receivedFileData = (Triple<PbxUser, byte[], byte[]>) in.readObject();
            Constants.delete(askFile);
            final PbxUser newUser = receivedFileData.first;
            final byte[] encodedPublicKey = receivedFileData.second;
            final byte[] signature = receivedFileData.third;

//            // CHECK IF THIS USER ALREADY EXISTS IN THE RECEIVED MACHINE SERIAL ID
//            File usersListFile = new File(directory.SHARED_PATH, Constants.USERS_FILE);
//            try (ObjectInputStream in2 = new ObjectInputStream(new FileInputStream(usersListFile))) {
//                List<User> userList = (List<User>) in2.readObject();
//                if (userList.contains(newUser)) {
//                    generateInvalidFile(parent, newUser);
//                    return;
//                }
//
//            } catch (IOException | ReflectiveOperationException ex) {
//                logger.error(ex.toString());
//                return;
//            }

            // VERIFY IF CERTIFICATE IS VALID
            try {
                newUser.getUserCertificate().checkValidity(Constants.getToday());
            } catch (CertificateException ex) {
                generateInvalidFile(parent, newUser);
                return;
            }

            try {
                // VERIFY SIGNATURE FROM SENT DATA
                Signature sig = Signature.getInstance("SHA1withRSA");
                sig.initVerify(newUser.getUserCertificate().getPublicKey());
                sig.update(encodedPublicKey);
                boolean verifyResult = sig.verify(signature);
                logger.info(""+verifyResult);
                if (!verifyResult) {
                    generateInvalidFile(parent, newUser);
                    return;
                }
            } catch (GeneralSecurityException ex) {
                generateInvalidFile(parent, newUser);
                return;
            }


            JLabel info = new JLabel();
            info.setText(newUser.toString());
            info.setBounds(20, 20, 430, 135);
            info.setIconTextGap(JLabel.RIGHT);
            info.setFont(Constants.FONT.deriveFont(14f));
            add(info);


            JLabel machineName = new JLabel();
            machineName.setText("Machine Name: " + newUser.getMachineName());
            machineName.setFont(Constants.FONT);
            machineName.setBounds(125, 100, 370, 50);
            add(machineName);


            JLabel allow = new JLabel(new ImageIcon(Constants.getAsset("allow.png")));
            allow.setLayout(null);
            allow.setBounds(110, 175, 122, 39);
            allow.setBackground(Color.black);
            allow.addMouseListener((OnMouseClick) e -> generateKeyFile(parent, newUser, registryAlgorithm, registryKey, encodedPublicKey));
            add(allow);

            JLabel deny = new JLabel(new ImageIcon(Constants.getAsset("deny.png")));
            deny.setLayout(null);
            deny.setBounds(260, 175, 122, 39);
            deny.setBackground(Color.black);
            deny.addMouseListener((OnMouseClick) e -> generateInvalidFile(parent, newUser));
            add(deny);


            askFile.deleteOnExit();


            setSize(470, 220);
            setUndecorated(true);
            getContentPane().setBackground(Color.white);
            setBackground(Color.white);
            setResizable(false);
            getRootPane().setBorder(BorderFactory.createLineBorder(new Color(100, 100, 100)));
            Utils.setComponentLocationOnCenter(this);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setVisible(true);

            new java.util.Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    dispose();
                }
            }, 90000);

        } catch (IOException | ReflectiveOperationException ex) {
            if (Constants.verbose) {
                logger.error("Error while validating requesting user", ex);
            }
        }
    }

    public static UserValidationWindow getInstance(final PReg registry, final File askFile) {
        String algorithm = registry.getPair().getPairAlgorithm();
        SecretKey key = registry.getPair().getPairKey();
        String sharedFolderName = registry.getPair().getSharedFolderFile().getName();

                UserValidationWindow newInstance = instances.get(registry);
        if (newInstance == null) {
            newInstance = new UserValidationWindow(algorithm, key, sharedFolderName, askFile);
            instances.put(registry, newInstance);
        } else {
            newInstance.setVisible(true);
            newInstance.toFront();
        }
        return newInstance;
    }

    private void generateKeyFile(File parent, PbxUser newUser, String algorithm, SecretKey directoryKey, byte[] encodedPublicKey) {
        try {

            // GENERATE SIGNED PUBLIC KEY FROM ENCODED BYTES
            PublicKey newUserPKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encodedPublicKey));


            // ENCRYPT SYMMETRIC KEY FROM DIRECTORY WITH PUBLIC KEY
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, newUserPKey);
            byte[] encodedKey = c.doFinal(directoryKey.getEncoded());


            // SAVE DIRECTORY'S ALGORITHM AND ENCRYPTED KEY IN FILE
            File keyFile = new File(parent, "»key" + newUser.getId());
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(keyFile))) {
                out.writeObject(Pair.of(algorithm, encodedKey));
            }

            dispose();

        } catch (GeneralSecurityException | IOException ex) {
            generateInvalidFile(parent, newUser);
        }
    }

    private void generateInvalidFile(File parent, PbxUser newUser) {
        try {
            File invalidFile = new File(parent, "»invalid" + newUser.getId());
            invalidFile.createNewFile();
            dispose();
        } catch (IOException ex) {
            if (Constants.verbose) {
                logger.error("Error while generating and sending an invalid file to the requesting user.", ex);
            }
        }
    }
}