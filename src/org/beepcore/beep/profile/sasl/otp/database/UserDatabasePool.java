
/*
 * UserDatabasePool.java            $Revision: 1.2 $ $Date: 2001/11/08 05:51:35 $
 *
 * Copyright (c) 2001 Invisible Worlds, Inc.  All rights reserved.
 *
 * The contents of this file are subject to the Blocks Public License (the
 * "License"); You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at http://www.beepcore.org/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied.  See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 */
package org.beepcore.beep.profile.sasl.otp.database;


import java.io.*;

import java.util.Hashtable;
import java.util.Properties;

import org.beepcore.beep.core.TuningProfile;
import org.beepcore.beep.profile.sasl.SASLException;
import org.beepcore.beep.util.Log;


/**
 * This class implements UserDatabaseManager with a simple
 * implementation.  More sophisticated implementations will
 * use the ProfileConfiguration to tell SASLOTPProfile to
 * load different classes to do this.  WARNING:  I have some
 * serious concerns about the public nature of these methods.
 * This won't stay this way, but the changes aren't cooked
 * enough to shove into the first beta.
 * 
 * @todo do something TuningProfile-ish to minimize the public
 * availability of these methods so that fake OTP DBs can't
 * be pushed in so damn easily.  There has to be a 'validation'
 * step and preferably, it'd have to happen through SASLOTPProfile
 * extending some class here or something.  Granted, if some
 * freak can execute code on your box then you're dead anyway,
 * but this makes it tougher...somewhat.
 * 
 */
public class UserDatabasePool implements UserDatabaseManager
{
    // Data 
    private Hashtable userpool = new Hashtable(4);

    /**
     * Method getUser This method is provided as a means
     * for users of the OTP databases to retrieve the information
     * contained in them, in the form of an instance of
     * UserDatabase.  Please note that ALGORITHM should in time be
     * added - to be part of how one looks up an OTP database (using
     * both the username and the algorithm).  The init-word and init-hex
     * commands, in their nature, don't really allow for it, so this'll
     * do for now, but in time it should be that way.  It certainly
     * wouldn't be a difficult thing to do.  This would also entail
     * evolving the way init-hex/word are processed, as well...which
     * is slightly trickier than doing a dual parameter lookup.
     * 
     * @param String username indicates which OTP database should 
     * be retrieved, based on who wishes to authenticate using it.
     *
     * @return UserDatabase the OTP database for the user specified.
     * 
     * @throws SASLException is thrown if the parameter is null or 
     * some error is encountered during the reading or processing
     * of the user's OTP database file.
     *
     */
    public UserDatabase getUser(String username) 
        throws SASLException
    {
        if (username == null) {
            throw new SASLException("Must supply a username to get an OTP DB");
        }

        if (userpool == null) {
            userpool = new Hashtable();
        }

        UserDatabase ud = (UserDatabase) userpool.get(username);

        if (ud == null) {
            Properties p = new Properties();

            try {
                Log.logEntry(Log.SEV_DEBUG,
                             ("Loading otp property file " + username
                              + OTP_SUFFIX));
                p.load(new FileInputStream(username + OTP_SUFFIX));
            } catch (IOException x) {
                Log.logEntry(Log.SEV_ERROR,
                             new String(UserDBNotFoundException.MSG
                                        + username));

                throw new UserDBNotFoundException(username);
            }

            try
            {
                ud = new UserDatabaseImpl(p.getProperty(OTP_ALGO),
                                          p.getProperty(OTP_LAST_HASH),
                                          p.getProperty(OTP_SEED),
                                          p.getProperty(OTP_AUTHENTICATOR),
                                          Integer.parseInt(p.getProperty(OTP_SEQUENCE)));
            }
            catch(Exception x)
            {
                throw new SASLException("OTP DB for "+username+"is corrupted");
            }
            userpool.put(username, ud);
            Log.logEntry(Log.SEV_DEBUG, p.toString());
            Log.logEntry(Log.SEV_DEBUG,
                         ("Stored otp settings for " + username));
        }
        Log.logEntry(Log.SEV_DEBUG, "Fetching User Database for " + username);
        return ud;
    }

    /**
     * Method addUser
     *
     * @param String username is the identity of the user for
     * whom this OTP database is used.
     * @param UserDatabase ud is the user database to be associated
     * with the user identified by 'username'.
     *
     */
    private void addUser(String username, UserDatabase ud)
        throws SASLException
    {
        userpool.put(username, ud);
        updateUserDB(ud);
    }

    public void addUser(String username, String algorithm,
                        String hash, String seed, String sequence)
        throws SASLException
    {
        UserDatabaseImpl udi = new UserDatabaseImpl(algorithm, hash, 
                                                    seed, username,
                                                    Integer.parseInt(sequence));
        addUser(username, udi);
    }

    /**
     * Method updateUserDB causes the long-term representation
     * (e.g. file) of the user's OTP database to be updated
     * after a successful authentication.  This entails a
     * decrementation of the sequence, and a storage of a new
     * 'last hash' value.
     *
     *
     * @param UserDatabase ud is the updated form of the OTP database.
     *
     * @throws SASLException if any issues are encountered during the
     * storage of the user's OTP DB.
     *
     */
    public void updateUserDB(UserDatabase ud)
            throws SASLException
    {
        try {
            Properties p = new Properties();
            String authenticator = ud.getAuthenticator();

            Log.logEntry(Log.SEV_DEBUG,
                         "Updating User DB on for=>" + authenticator);
            p.setProperty(OTP_AUTHENTICATOR, authenticator);
            p.setProperty(OTP_LAST_HASH, ud.getLastHashAsString());
            p.setProperty(OTP_SEED, ud.getSeed());
            p.setProperty(OTP_MECH, "otp");
            p.setProperty(OTP_ALGO, ud.getAlgorithmName());
            p.setProperty(OTP_SEQUENCE, Integer.toString(ud.getSequence()));
            p.store(new FileOutputStream(authenticator + OTP_SUFFIX), 
                    OTP_HEADER);
        } catch (IOException x) {
            Log.logEntry(Log.SEV_ERROR, x);

            throw new SASLException(x.getMessage());
        }
    }
    
    /**
     * Method removeUserDB causes the long-term representation
     * (e.g. file) of the user's OTP database to be removed.
     *
     * @param String authenticator, the user of the OTP database.
     *
     * @throws SASLException if any issues are encountered during the
     * removal of the user's OTP DB.
     *
     */
    public void removeUserDB(String authenticator)
            throws SASLException
    {
        // @todo implement pending thinking about the issue.        
    }

    /**
     * Method populateUserDatabases is a simple stub routine used
     * to test the library.  It simply creates two temporary OTP
     * databases.     
     *
     * @throws SASLException if it encounters any issues storing
     * the OTP databases.
     *
     */
    public void populateUserDatabases() 
        throws SASLException
    {
        try {
            Properties p = new Properties();

            p.setProperty(OTP_AUTHENTICATOR, "IW_User");
            p.setProperty(OTP_LAST_HASH, "7CD34C1040ADD14B");
            p.setProperty(OTP_SEED, "alpha1");
            p.setProperty(OTP_MECH, "otp");
            p.setProperty(OTP_ALGO, "otp-md5");
            p.setProperty(OTP_SEQUENCE, "1");
            p.store(new FileOutputStream("IW_User.otp"), OTP_HEADER);

            p = new Properties();

            p.setProperty(OTP_AUTHENTICATOR, "IW_User2");
            p.setProperty(OTP_LAST_HASH, "82AEB52D943774E4");
            p.setProperty(OTP_SEED, "correct");
            p.setProperty(OTP_MECH, "otp");
            p.setProperty(OTP_ALGO, "otp-sha1");
            p.setProperty(OTP_SEQUENCE, "1");
            p.store(new FileOutputStream("IW_User2.otp"), OTP_HEADER);
        } catch (IOException x) {
            Log.logEntry(Log.SEV_ERROR, x);
        }
    }
}
