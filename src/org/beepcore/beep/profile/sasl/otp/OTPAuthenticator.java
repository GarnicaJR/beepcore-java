/*
 * OTPAuthenticator.java  $Revision: 1.10 $ $Date: 2001/11/08 05:51:34 $
 *
 * Copyright (c) 2001 Invisible Worlds, Inc.  All rights reserved.
 *
 * The contents of this file are subject to the Blocks License (the
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
package org.beepcore.beep.profile.sasl.otp;


import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import java.util.Hashtable;
import java.util.StringTokenizer;

import org.beepcore.beep.core.*;
import org.beepcore.beep.util.*;
import org.beepcore.beep.profile.sasl.*;
import org.beepcore.beep.profile.sasl.otp.algorithm.*;
import org.beepcore.beep.profile.sasl.otp.algorithm.md5.*;
import org.beepcore.beep.profile.sasl.otp.algorithm.sha1.*;
import org.beepcore.beep.profile.sasl.otp.database.UserDatabase;
import org.beepcore.beep.profile.sasl.otp.database.*;

/**
 * This class encapsulates the state associated with
 * an ongoing OTP Authentication, and provides methods
 * to handle the exchange.
 *
 * @author Eric Dixon
 * @author Huston Franklin
 * @author Jay Kint
 * @author Scott Pead
 * @version $Revision: 1.10 $, $Date: 2001/11/08 05:51:34 $
 *
 */
class OTPAuthenticator implements MessageListener, ReplyListener {

    // Constants
    // Authentication States
    // Can be confusing..here are the sequences for state changes
    // It's not linear, the states only get changed here.
    // This prevents me from having to check if it's an 'initiator'
    // or 'listener' - the states imply that ;)
    // Initiator STARTED=>CHALLENGE=>COMPLETE
    // Listener STARTED=>ID=>RESPONSE=>COMPLETE
    static final int STATE_UNKNOWN = 0;
    static final int STATE_STARTED = 1;
    static final int STATE_ID = 2;
    static final int STATE_CHALLENGE = 3;
    static final int STATE_RESPONSE = 4;
    static final int STATE_COMPLETE = 5;
    static final int STATE_ABORT = 6;

    // Err Messages
    static final String ERR_PEER_ABORTED =
        "Our BEEP Peer has aborted this authentication sequence";
    static final String ERR_IDENTITY_PARSE_FAILURE =
        "Invalid identity information submitted for OTP Authentication";
    static final String ERR_NULL_ID =
        "Invalid Authentication Info Provided";
    static final String ERR_OTP_AUTH_FAILURE =
        "Authentication Failure: Password hash doesn't match";
    static final String ERR_OTP_STATE =
        "Authentication Failure: Illegal OTP State Transition";
    static final String ERR_UNEXPECTED_MESSAGE = 
        "Unexpected SASL-OTP Message";
    static final String ERR_INIT =
        "Error while parsing init-hex or init-word=>";
    static final String ERR_UNKNOWN_COMMAND = "Unknown SASL OTP Command=>";
    static final String ERR_CONCURRENT =
        "Authentication for that user already in progress";
    private static final String ERR_SEQUENCE_ZERO =
        "Authentication unable to proceed because the user's SASL OTP Sequence is 0.";
    private static String COLON = ":";
    
    // Other
    static final String EXT = "ext";
    static final String HEX = "hex:";
    static final String INIT_HEX = "init-word:";
    static final String INIT_WORD = "init-hex:";
    static final String OTP_AUTH = "OTPAuthenticator";
    static final String SPACE = " ";
    static final String WORD = "word:";
    static final char SPACE_CHAR = ' ';

    // Data
    private int state;
    private Algorithm algorithm;
    private Channel channel;
    private Hashtable credential;
    private SASLOTPProfile profile;
    private String authenticated, authorized, initData = null, password;
    private UserDatabase database;

    /**
     * Listener API
     *
     * All of the routines below, but prior to the Initiator API,
     * are the Listener calls
     */
    OTPAuthenticator(SASLOTPProfile otpProfile)
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "Creating Listener OTP Authenticator");

        authenticated = null;
        authorized = null;
        credential = new Hashtable();
        database = null;
        password = null;
        profile = otpProfile;
        state = STATE_UNKNOWN;

        credential.put(SessionCredential.AUTHENTICATOR_TYPE,
                       SASLOTPProfile.MECHANISM);
    }

    /**
     * API for both Listener and Initiator
     */
    void started(Channel ch) 
        throws SASLException
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                     "Starting OTP Authenticator");

        if (state != STATE_UNKNOWN) {
            throw new SASLException(ERR_OTP_STATE);
        }
        state = STATE_STARTED;
        channel = ch;
        channel.setMessageListener(this);
    }

    /**
     * Listener API
     *
     * Receive IDs, respond with a Challenge or Exception
     */
    synchronized Blob receiveIDs(String data)
        throws SASLException
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "OTP Authenticator Receiving IDs");

        // If we're listening, the last state we should
        // have gotten to was STATE_STARTED (after the channel start)
        if (state != STATE_STARTED) {
            abort(ERR_OTP_STATE);
        }

        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, "Data is" + data);

        int i = data.charAt(0);    // data.indexOf(SPACE_CHAR);

        if (i == -1) {
            abort(ERR_IDENTITY_PARSE_FAILURE);
        } else if (i == 0) {
            authorized = null;
        } else {
            int index = 0;
            try
            {
                index = data.indexOf(0);
                authorized = data.substring(0, index);
            }
            catch(Exception x)
                //            catch(IndexOutOfBoundsException x)
            {
                authorized = null;
            }
        }

        authenticated = data.substring(data.indexOf(0) + 1);
        if(!profile.validateIdentity(authenticated, this))
        {
            abort(ERR_CONCURRENT);
        }

        if (authenticated == null) {
            abort(ERR_NULL_ID);
        }

        Log.logEntry(Log.SEV_DEBUG, 
                     "Fetching DB for " + authenticated);

        try
        {
            database = profile.getUserDatabase().getUser(authenticated);
            algorithm = profile.getAlgorithm(database.getAlgorithmName());
        }
        catch(SASLException x)
        {
            abort(x.getMessage());
        }

        credential.put(SessionCredential.ALGORITHM, algorithm.getName());

        // @todo we may want to clear the DB on file or something here,
        // consider it as we consider how an abstract library and more
        // implementation specific configurations play together, a
        // SASLOTPDatabase interface or something (such as what's in the
        // database package below sasl.otp might be used.  I've got
        // update and stuff, but we may want 'purge', and then extentions
        // in the init method of SASLOTPProfile to potentially load different
        // UserDatabase managing 'things'...so someone can load something
        // other than UserDictionaryPool at init/config time and use it.
        if ((database.getSequence()) == 0) {
            abort(ERR_SEQUENCE_ZERO);
        }

        // Assign data
        state = STATE_ID;

        credential.put(SessionCredential.AUTHENTICATOR, authenticated);

        if (authorized != null) {
            credential.put(SessionCredential.AUTHORIZED, authorized);
        }

        credential.put(SessionCredential.AUTHENTICATOR_TYPE,
                       SASLOTPProfile.MECHANISM);

        StringBuffer challenge = new StringBuffer(128);

        challenge.append(algorithm.getName());
        challenge.append(SPACE);
        challenge.append(database.getSequence());
        challenge.append(SPACE);
        challenge.append(database.getSeed().toLowerCase());
        challenge.append(SPACE);
        challenge.append(EXT);
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "Generated Challenge=>" + challenge.toString());

        try
        {
            return new Blob(Blob.STATUS_NONE,challenge.toString());
        }
        catch(Exception x)
        {}
        // This will throw a SASLException
        abort("Failed to issue SASL OTP challenge");
        return null;
    }

    /**
     * Listener API
     *
     * Receive response to challenge, figure out if it
     * works or throw an exception if it doesn't.
     * 
     * @todo must handle the init-hex and init-word responses
     * if they're there.
     */
    synchronized SessionCredential validateResponse(String response)
        throws SASLException
    {
        boolean doInit = false;
        byte responseHash[] = null;
        
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "OTP Authenticator validating response");

        // If we're listening, the last state we should
        // have gotten to was STATE_ID (receiving the IDs)
        if (state != STATE_ID) {
            abort(ERR_OTP_STATE);
        }

        // Check results
        // The 'last hash' is the result of the hash function
        // on password+seed for sequence+1 times.
        // The pwd hash this time is for password_seed sequence
        // times.  By taking it and hashing it once, we should
        // get the last hash and that's how we verify.  Semi-slick.
        // So hooray for assymetric hash functions
        if (response.indexOf(profile.HEX_INIT) != -1 ||
            response.indexOf(profile.WORD_INIT) != -1 )
        {
            return validateInitResponse(response);
        }

        // Identify type of message, WORD or HEX
        if (response.indexOf(WORD) != -1) {
            response = response.substring(WORD.length());
            long l = OTPDictionary.convertWordsToHash(response);
            responseHash = profile.convertLongToBytes(l);            
            Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                         "Hacked response=>" + response);
        } else if (response.indexOf(HEX) != -1) {
            response = response.substring(HEX.length());
            responseHash = profile.convertHexToBytes(response);
            Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                         "Hacked response=>" + response);
        } else {
            abort(ERR_UNEXPECTED_MESSAGE);
        }
            
        if ((database.getSequence()) == 0) {
            throw new SequenceZeroFailure();
        }
        
        SessionCredential cred = validateHash(responseHash);
        database.updateLastHash(profile.convertBytesToHex(responseHash));
        profile.getUserDatabase().updateUserDB(database);
        return cred;
    }
    
    private SessionCredential validateHash(byte hash[])
        throws SASLException
    {
        if ((database.getSequence()) == 0) {
            throw new SequenceZeroFailure();
        }

        // Get the two hashes
        byte nextHash[] = database.getLastHash();
        byte responseHash[] = algorithm.generateHash(hash);
        Log.logEntry(Log.SEV_DEBUG_VERBOSE,"Test====>"+profile.convertBytesToHex(responseHash));
        Log.logEntry(Log.SEV_DEBUG_VERBOSE,"Control=>"+profile.convertBytesToHex(nextHash));
        boolean match = true;
        for(int i = 0; i < 8; i++)
        {
            if(nextHash[i] != responseHash[i])
                match = false;
        }
        if(!match)
            throw new SASLException(ERR_OTP_AUTH_FAILURE);

        // Success
        state = STATE_COMPLETE;
        if(credential.size()==0)
            return null;
        return new SessionCredential(credential);
    }
    
    synchronized SessionCredential validateInitResponse(String response) 
        throws SASLException
    {
        Log.logEntry(Log.SEV_DEBUG, "Validating init-* response");
        // Extract the various elements of the request
        String oldHashData;
        byte oldHash[]=null;
        String newHashData;
        String newParms;
        int i = response.indexOf(COLON);
        String command;
        
        try
        {   
            // Extract Chunks of data
            StringTokenizer st = new StringTokenizer(response,COLON);
            command = st.nextToken();
            oldHashData = st.nextToken();
            newParms = st.nextToken();
            newHashData = st.nextToken();
            Log.logEntry(Log.SEV_DEBUG,"Command=>"+command);
            Log.logEntry(Log.SEV_DEBUG,"OldHashData=>"+oldHashData);
            Log.logEntry(Log.SEV_DEBUG,"newParms=>"+newParms);
            Log.logEntry(Log.SEV_DEBUG,"newHashData=>"+newHashData);
                        
            // Validate login
            Algorithm a = profile.getAlgorithm(database.getAlgorithmName());
            if(profile.HEX_INIT.startsWith(command))//command.indexOf(profile.HEX_INIT) != -1)
            {
                Log.logEntry(Log.SEV_DEBUG,"CMD is "+profile.HEX_INIT);
                oldHash = profile.convertHexToBytes(oldHashData);
            }
            else if(profile.WORD_INIT.startsWith(command))//command.indexOf(profile.WORD_INIT) != -1)
            {
                Log.logEntry(Log.SEV_DEBUG,"CMD is "+profile.WORD_INIT);
                // @todo obviate the 2nd step when you get a chance
                long l = OTPDictionary.convertWordsToHash(oldHashData);
                oldHash = profile.convertLongToBytes(l);
            }
            else
            {
                abort(ERR_UNKNOWN_COMMAND+command);
            }
            Log.logEntry(Log.SEV_DEBUG,"Retrieved from init-* oldHash=>"+profile.convertBytesToHex(oldHash));
            
            // Compare the hash and fail if it doesn't match
            SessionCredential cred = validateHash(oldHash);

            // Extract the stuff
            st = new StringTokenizer(newParms);
            String algorithm = st.nextToken();
            String sequence = st.nextToken();
            String seed = st.nextToken().toLowerCase();
            if(!OTPGenerator.validateSeed(seed))
                abort("Invalid Seed");
            st = new StringTokenizer(newParms);
            // Now do even weirder update of the db.
            Log.logEntry(Log.SEV_DEBUG,"Auth=>"+authenticated);
            Log.logEntry(Log.SEV_DEBUG,"Hash=>"+newHashData);
            profile.getUserDatabase().addUser(authenticated, algorithm,
                                              newHashData, seed, sequence);
            Log.logEntry(Log.SEV_DEBUG, "Successful Authentication!");
            return cred;            
        }
        catch(Throwable t)
        {
            throw new SASLException(ERR_INIT+response+t.getMessage());   
        }
    }

    /**
     * Initiator API
     *
     * ALL of the routines below are the Initiator calls.
     */
    OTPAuthenticator(SASLOTPProfile otpProfile,
                     UserDatabase db, String pwd,
                     String authorizedId, String authenticateId)
    {
        init(otpProfile, db, pwd, authorizedId, authenticateId);
    }
    
    private void init(SASLOTPProfile otpProfile,
                      UserDatabase db, String pwd,
                      String authorizedId, String authenticateId)
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "OTP Authenticator Initiator Construtor");

        authenticated = authenticateId;
        authorized = authorizedId;
        credential = new Hashtable();
        database = db;

        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "Dict.getA()" + database.getAlgorithmName());
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "Dict.getA()"
                     + profile.getAlgorithm(database.getAlgorithmName()));

        algorithm = profile.getAlgorithm(database.getAlgorithmName());
        profile = otpProfile;
        password = pwd;
        state = STATE_UNKNOWN;

        credential.put(SessionCredential.AUTHENTICATOR_TYPE,
                       SASLOTPProfile.MECHANISM);
        credential.put(SessionCredential.ALGORITHM, algorithm.getName());
        credential.put(SessionCredential.AUTHENTICATOR, authenticateId);

        if (authorizedId != null) {
            credential.put(SessionCredential.AUTHORIZED, authorizedId);
        }
    }
    
    // Weird init version
    OTPAuthenticator(SASLOTPProfile otpProfile,
                     UserDatabase db, String pwd,
                     String authorizedId, String authenticateId,
                     String newAlgorithm, String newHash,
                     String newSeed, String newSequence)
    {
        StringBuffer sb = new StringBuffer(128);
        sb.append(COLON);
        sb.append(newAlgorithm);
        sb.append(SPACE);
        sb.append(newSequence);
        sb.append(SPACE);
        sb.append(newSeed.toLowerCase());
        sb.append(COLON);
        sb.append(newHash);
        initData = sb.toString();
        init(otpProfile, db, pwd, authorizedId, authenticateId);
    }

    /**
     * Initiator API used by SASL-OTP consumers that don't use
     * the data on the startChannel option
     *
     * If it works, we should get a challenge in our receiveRPY
     * callback ;)
     */
    void sendIdentity(String authorizeId, String authenticateId)
        throws SASLException
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "OTP Authenticator sending Identities");

        // Grok and validate the parameters
        int limit = authenticateId.length();

        if (authorizeId != null) {
            limit += authorizeId.length();
        }

        StringBuffer temp = new StringBuffer(limit);

        if (authorizeId != null) {
            temp.append(authorizeId);
        } else {
            temp.append((char) 0);
        }

        temp.append(authenticateId);
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                     "AuthOTP Using=>" + temp.toString() + "<=");
        Blob blob = new Blob(Blob.STATUS_NONE, temp.toString());
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                     "AuthOTP Using=>" + blob.toString() + "<=");
        try {
            channel.sendMSG(new StringDataStream(blob.toString()), (ReplyListener) this);
        } catch (BEEPException x) {
            abort(x.getMessage());
        }
    }

    /**
     * Initiator API
     * Receive Challenge, respond with a hash.
     */
    synchronized void receiveChallenge(Blob blob)
        throws SASLException
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "OTP Authenticator received Challenge");

        // If we're initiating, the last state we should
        // have gotten to was STATE_STARTED
        if (state != STATE_STARTED) {
            abortNoThrow(ERR_OTP_STATE);
        }
        
        if(blob.getStatus().equals(Blob.ABORT))
        {
            abort(ERR_PEER_ABORTED);
        }
        String challenge = blob.getData();

        // Parse Challenge, provide response
        state = STATE_CHALLENGE;

        int sequence = 0;
        String seed = null, algo = null;

        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                     "Tokenizing=>" + challenge);

        StringTokenizer st = new StringTokenizer(challenge);

        if (st.countTokens() != 4) {
            abort("Failed to understand server's Challenge"
                                    + st.countTokens());
        }

        algo = st.nextToken();
        algorithm = profile.getAlgorithm(algo);

        if (algorithm == null) {
            abort("Unrecognized algorithm in server challenge");
        }

        sequence = Integer.parseInt(st.nextToken());
        seed = st.nextToken().toLowerCase();
        if(!OTPGenerator.validateSeed(seed))
            abort("Invalid Seed");

        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "Algo is=>" + algo + " seed is=>" + seed + " seq=>"
                     + sequence);

        String phrase = new String(seed + password);
        password = null;
        byte response[] = null, temp[];

        temp = phrase.getBytes();

        for (int i = 0; i < sequence; i++) {
            response = algorithm.generateHash(temp);
            temp = response;
        }

        profile.printHex(temp);
        long l = profile.convertBytesToLong(temp);
        phrase = new String(WORD + OTPDictionary.convertHashToWords(l));
        
        Log.logEntry(Log.SEV_DEBUG, 
                     "Prelim response is =>" + phrase + "<=");

        // IF this is an init request   
        if(initData != null)
        {
            StringBuffer sb = new StringBuffer(128);
            sb.append(profile.HEX_INIT);
            sb.append(profile.convertBytesToHex(temp));
            sb.append(initData);
            phrase = sb.toString();
            Log.logEntry(Log.SEV_DEBUG,"Produced INIT response of "+phrase);
        }
        try
        {
            blob = new Blob(Blob.STATUS_CONTINUE, phrase);
            channel.sendMSG(new StringDataStream(blob.toString()), this);
        }
        catch(BEEPException x)
        {
            throw new SASLException("Unable to send response to challenge");
        }
    }

    /**
     * Initiator API
     * Receive response to challenge, figure out if it
     * works or throw an exception if it doesn't.
     */
    synchronized SessionCredential receiveCompletion(String response)
        throws SASLException
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                     "OTP Authenticator Completing!");

        // If we're initiating, the last state we should
        // have gotten to was STATE_CHALLENGE
        if (state != STATE_CHALLENGE) {
            abort(ERR_OTP_STATE);
        }

        state = STATE_COMPLETE;

        return new SessionCredential(credential);
    }

    void abort(String message)
        throws SASLException
    {
        Log.logEntry(Log.SEV_ERROR, OTP_AUTH, 
                     "Aborting OTP Authenticator because " + message);
        state = STATE_ABORT;
        throw new SASLException(message);
    }
    
    /**
     * This is designed to be called by initiator methods
     * that want to abort the authentication by sending
     * a MSG with a <blob status='abort'>reason</blob> 
     * to their peer.
     */
    void abortNoThrow(String message)
    {
        Log.logEntry(Log.SEV_ERROR, OTP_AUTH, 
                     "Aborting OTP Authenticator because " + message);
        state = STATE_ABORT;
    }

    /**
     * Method receiveMSG
     * Listener API
     *
     * We receive MSGS - IDs, and extended responses (hash)
     * in response to our challenges and stuff.
     * 
     * @param Message message is the data we've received.
     * We parse it to see if it's identity information, an
     * abort, or otherwise.
     * 
     * @throws BEEPError if an ERR message is generated
     * that's relative to the BEEP protocol is encountered.
     */
    public void receiveMSG(Message message) throws BEEPError
    {
        try
        {
            Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                         "OTP Authenticator.receiveMSG");

            String data = null;
            Blob blob = null;
            
            if ((state != STATE_STARTED) && (state != STATE_ID)){
                abort(ERR_OTP_STATE);
            }

            // Read the data in the message and produce a Blob
            try
            {
                InputStream is = message.getDataStream().getInputStream();
                int limit = is.available();
                byte buff[] = new byte[limit];
                is.read(buff);
                blob = new Blob(new String(buff));
                data = blob.getData();
            }
            catch(IOException x)
            {
                abort(x.getMessage());
            }

            Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                         "MSG DATA=>" + data);
            String status = blob.getStatus();

            if ((status != null)
                && status.equals(SASLProfile.SASL_STATUS_ABORT)) {
                abort(ERR_PEER_ABORTED);
            }

            if (state == STATE_STARTED) {
                Blob reply = receiveIDs(data);
                try {
                    message.sendRPY(new StringDataStream(reply.toString()));
                } catch (BEEPException x) {
                    throw new SASLException(x.getMessage());
                }
                return;
            }

            // Process the user's password and stuff.
            SessionCredential cred = null;
            cred = validateResponse(data);

            if (cred != null) {
                profile.finishListenerAuthentication(cred, channel.getSession());

                state = STATE_COMPLETE;

                Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                             "Wow, cool!!! " + channel.getSession()
                             + " is valid for\n" + cred.toString());
                try
                {
                    message.sendRPY(new StringDataStream(new Blob(Blob.STATUS_COMPLETE).toString()));
                    channel.setMessageListener(null);
                }
                catch(BEEPException x)
                {
                    profile.failListenerAuthentication(channel.getSession(),
                                                       authenticated);
                    abortNoThrow(x.getMessage());
                    message.getChannel().getSession().terminate(x.getMessage());
                    return;
                }
            }
        }
        catch(SASLException s)
        {
            try
            {
                // Gotta reply, might as well send the abort, even
                // if it's redundant
                profile.failListenerAuthentication(channel.getSession(),
                                                   authenticated);
                Blob reply  = new Blob(Blob.STATUS_ABORT, s.getMessage());
                message.sendRPY(new StringDataStream(reply.toString()));
//                channel.setDataListener(null);
            }
            catch(BEEPException x)
            {
                message.getChannel().getSession().terminate(s.getMessage());
            }            
        }
    }

    /**
     * Method receiveRPY
     * Initiator API
     *
     * We receive replies to our ID messages, and to our extended responses
     * Initiator API
     *
     * @param Message message is the data we've received.
     * We parse it to see if it's identity information, an
     * abort, or otherwise.
     * 
     */
    public void receiveRPY(Message message)
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                     "OTP Authenticator.receiveRPY");

        Blob blob = null;
        // Don't send an abort if we got one.
        boolean sendAbort = true;
        
        try
        {
            if ((state != STATE_STARTED) && (state != STATE_CHALLENGE)
                && (state != STATE_COMPLETE)) {
                sendAbort = true;
            }

            try
            {
                InputStream is = message.getDataStream().getInputStream();
                int limit = is.available();
                byte buff[] = new byte[limit];
                is.read(buff);
                blob = new Blob(new String(buff));
            }
            catch(IOException x)
            {
                abort(x.getMessage());
            }

            String status = blob.getStatus();

            if ((status != null)
                && status.equals(SASLProfile.SASL_STATUS_ABORT)) {
                Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                             "OTPAuthenticator receiveRPY got an RPY=>"
                             + blob.getData());
                sendAbort = false;
                abort(ERR_PEER_ABORTED + blob.getData());
            }

            // If this reply is a reply to our authenticate message
            if (state == STATE_STARTED) 
            {
                receiveChallenge(blob);
                return;
            }
            // If it's a reply to our authentication request
            else if(blob.getStatus() != Blob.ABORT)
            {
                // Success case
                // Set creds...
                profile.finishInitiatorAuthentication(new SessionCredential(credential),
                                                      channel.getSession());

                synchronized (this) {
                    this.notify();
                }
                return;
            }
            else
            {
                // Error case
                abort(ERR_UNKNOWN_COMMAND);
                return;
            }
        }
        catch(Exception x)
        {
            Log.logEntry(Log.SEV_ERROR, x);
            synchronized (this) {
                this.notify();
            }
            // Throw an error
            // Do a flag to indicate when this happens?
            if(sendAbort)
            {
                try
                {
                    Blob a = new Blob(Blob.STATUS_ABORT, x.getMessage());
                    channel.sendMSG(new StringDataStream(a.toString()),
                                    this);
                }
                catch(BEEPException y)
                {
                    message.getChannel().getSession().terminate(y.getMessage());
                }
            }
        }
    }

    /**
     * Method receiveERR
     * 
     * Initiator API
     *
     * Generally we get this if our challenge fails or
     * our authenticate identity is unacceptable or the
     * hash we use isn't up to snuff etc.
     * 
     * @param Message message is the data we've received.
     * We parse it to see if it's identity information, an
     * abort, or otherwise.
     * 
     */
    public void receiveERR(Message message)
    {
        Log.logEntry(Log.SEV_DEBUG, OTP_AUTH, 
                     "OTP Authenticator.receiveERR");

        try {
            InputStream is = message.getDataStream().getInputStream();
            int limit = is.available();
            byte buff[] = new byte[limit];

            is.read(buff);
            Blob b = new Blob(new String(buff));
            Log.logEntry(Log.SEV_DEBUG, OTP_AUTH,
                         "ERR received=>\n" + b.getData());
            abort(new String(buff));
            
            synchronized (this) {
                this.notify();
            }
        } catch (Exception x) {
            abortNoThrow(x.getMessage());
        }
    }

    /*
    * Method receiveANS
    * This method should never be called
    * 
    * @param Message message is the data we've received.
    * We parse it to see if it's identity information, an
    * abort, or otherwise.
    * 
    */
    public void receiveANS(Message message)
    {
        message.getChannel().getSession().terminate(ERR_UNEXPECTED_MESSAGE);
    }

    /*
    * Method receiveNUL
    * This method should never be called
    * 
    * @param Message message is the data we've received.
    * We parse it to see if it's identity information, an
    * abort, or otherwise.
    * 
    * @throws TerminateException if some issue with the Message
    * that's relative to the BEEP protocol is encountered.
    */
    public void receiveNUL(Message message)
    {
        message.getChannel().getSession().terminate(ERR_UNEXPECTED_MESSAGE);
    }
}
