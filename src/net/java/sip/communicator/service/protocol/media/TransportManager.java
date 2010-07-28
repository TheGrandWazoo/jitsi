/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.service.protocol.media;

import java.net.*;

import net.java.sip.communicator.service.neomedia.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

/**
 * <tt>TransportManager</tt>s are responsible for allocating ports, gathering
 * local candidates and managing ICE whenever we are using it.
 *
 * @param <U> the peer extension class like for example <tt>CallPeerSipImpl</tt>
 * or <tt>CallPeerJabberImpl</tt>
 *
 * @author Emil Ivov
 */
public abstract class TransportManager<U extends MediaAwareCallPeer<?, ?, ?>>
{
    /**
     * The <tt>Logger</tt> used by the <tt>TransportManager</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger = Logger
                    .getLogger(TransportManager.class.getName());

    /**
     * The minimum port number that we'd like our RTP sockets to bind upon.
     */
    private static int minMediaPort = 5000;

    /**
     * The maximum port number that we'd like our RTP sockets to bind upon.
     */
    private static int maxMediaPort = 6000;

    /**
     * The port that we should try to bind our next media stream's RTP socket
     * to.
     */
    private static int nextMediaPortToTry = minMediaPort;

    /**
     * The RTP/RTCP socket couple that this media handler should use to send
     * and receive audio flows through.
     */
    private StreamConnector audioStreamConnector = null;

    /**
     * The RTP/RTCP socket couple that this media handler should use to send
     * and receive video flows through.
     */
    private StreamConnector videoStreamConnector = null;

    /**
     * The {@link MediaAwareCallPeer} whose traffic we will be taking care of.
     */
    private U callPeer;

    /**
     * Creates a new instance of this transport manager, binding it to the
     * specified peer.
     *
     * @param callPeer the {@link MediaAwareCallPeer} whose traffic we will be
     * taking care of.
     */
    protected TransportManager(U callPeer)
    {
        this.callPeer     = callPeer;
    }

    /**
     * Returns the <tt>StreamConnector</tt> instance that this media handler
     * should use for streams of the specified <tt>mediaType</tt>. The method
     * would also create a new <tt>StreamConnector</tt> if no connector has
     * been initialized for this <tt>mediaType</tt> yet or in case one
     * of its underlying sockets has been closed.
     *
     * @param mediaType the MediaType that we'd like to create a connector for.
     *
     * @return this media handler's <tt>StreamConnector</tt> for the specified
     * <tt>mediaType</tt>.
     *
     * @throws OperationFailedException in case we failed to initialize our
     * connector.
     */
    public StreamConnector getStreamConnector(MediaType mediaType)
        throws OperationFailedException
    {
        if (mediaType == MediaType.AUDIO)
        {
            if ( audioStreamConnector == null
                 || audioStreamConnector.getDataSocket().isClosed()
                 || audioStreamConnector.getControlSocket().isClosed())
            {
                audioStreamConnector = createStreamConnector();
            }

            return audioStreamConnector;
        }
        else
        {
            if ( videoStreamConnector == null
                 || videoStreamConnector.getDataSocket().isClosed()
                 || videoStreamConnector.getControlSocket().isClosed())
            {
                videoStreamConnector = createStreamConnector();
            }

            return videoStreamConnector;
        }
    }

    /**
     * Closes both the control and the data socket of the specified connector
     * and releases its reference (if it wasn't the case already).
     *
     * @param mediaType the type of the connector we'd like to close.
     */
    public void closeStreamConnector(MediaType mediaType)
    {
        StreamConnector connector
            = (mediaType == MediaType.VIDEO)
                ? videoStreamConnector
                : audioStreamConnector;

        synchronized(connector)
        {
            if(connector != null)
            {
                audioStreamConnector.getDataSocket().close();
                audioStreamConnector.getControlSocket().close();
                audioStreamConnector = null;
            }
        }

    }

    /**
     * Creates a media <tt>StreamConnector</tt>. The method takes into account
     * the minimum and maximum media port boundaries.
     *
     * @return a new <tt>StreamConnector</tt>.
     *
     * @throws OperationFailedException if we fail binding the the sockets.
     */
    protected StreamConnector createStreamConnector()
        throws OperationFailedException
    {
        NetworkAddressManagerService nam
            = ProtocolMediaActivator.getNetworkAddressManagerService();

        InetAddress intendedDestination = getIntendedDestination(getCallPeer());

        InetAddress localHostForPeer = nam.getLocalHost(intendedDestination);

        //make sure our port numbers reflect the configuration service settings
        initializePortNumbers();

        //create the RTP socket.
        DatagramSocket rtpSocket = null;
        try
        {
            rtpSocket = nam.createDatagramSocket( localHostForPeer,
                            nextMediaPortToTry, minMediaPort, maxMediaPort);
        }
        catch (Exception exc)
        {
            throw new OperationFailedException(
                "Failed to allocate the network ports necessary for the call.",
                OperationFailedException.INTERNAL_ERROR, exc);
        }

        //make sure that next time we don't try to bind on occupied ports
        nextMediaPortToTry = rtpSocket.getLocalPort() + 1;

        //create the RTCP socket, preferably on the port following our RTP one.
        DatagramSocket rtcpSocket = null;
        try
        {
            rtcpSocket = nam.createDatagramSocket(localHostForPeer,
                            nextMediaPortToTry, minMediaPort, maxMediaPort);
        }
        catch (Exception exc)
        {
           throw new OperationFailedException(
                "Failed to allocate the network ports necessary for the call.",
                OperationFailedException.INTERNAL_ERROR, exc);
        }

        //make sure that next time we don't try to bind on occupied ports
        nextMediaPortToTry = rtcpSocket.getLocalPort() + 1;

        if (nextMediaPortToTry > maxMediaPort -1)// take RTCP into account.
            nextMediaPortToTry = minMediaPort;

        //create the RTCP socket
        DefaultStreamConnector connector = new DefaultStreamConnector(
                        rtpSocket, rtcpSocket);

        return connector;
    }

    /**
     * (Re)Sets the <tt>minPortNumber</tt> and <tt>maxPortNumber</tt> to their
     * defaults or to the values specified in the <tt>ConfigurationService</tt>.
     */
    protected void initializePortNumbers()
    {
        //first reset to default values
        minMediaPort = 5000;
        maxMediaPort = 6000;

        //then set to anything the user might have specified.
        String minPortNumberStr
            = ProtocolMediaActivator.getConfigurationService()
                .getString(OperationSetBasicTelephony
                    .MIN_MEDIA_PORT_NUMBER_PROPERTY_NAME);

        if (minPortNumberStr != null)
        {
            try
            {
                minMediaPort = Integer.parseInt(minPortNumberStr);
            }
            catch (NumberFormatException ex)
            {
                logger.warn(minPortNumberStr
                            + " is not a valid min port number value. "
                            + "using min port " + minMediaPort);
            }
        }

        String maxPortNumberStr
                = ProtocolMediaActivator.getConfigurationService()
                    .getString(OperationSetBasicTelephony
                        .MAX_MEDIA_PORT_NUMBER_PROPERTY_NAME);

        if (maxPortNumberStr != null)
        {
            try
            {
                maxMediaPort = Integer.parseInt(maxPortNumberStr);
            }
            catch (NumberFormatException ex)
            {
                logger.warn(maxPortNumberStr
                            + " is not a valid max port number value. "
                            +"using max port " + maxMediaPort,
                            ex);
            }
        }
    }

    /**
     * Returns the <tt>InetAddress</tt> that we are using in one of our
     * <tt>StreamConnector</tt>s or, in case we don't have any connectors yet
     * the address returned by the our network address manager as the best local
     * address to use when contacting the <tt>CallPeer</tt> associated with this
     * <tt>MediaHandler</tt>. This method is primarily meant for use with the
     * o= and c= fields of a newly created session description. The point is
     * that we create our <tt>StreamConnector</tt>s when constructing the media
     * descriptions so we already have a specific local address assigned to them
     * at the time we get ready to create the c= and o= fields. It is therefore
     * better to try and return one of these addresses before trying the net
     * address manager again ang running the slight risk of getting a different
     * address.
     *
     * @return an <tt>InetAddress</tt> that we use in one of the
     * <tt>StreamConnector</tt>s in this class.
     */
    public InetAddress getLastUsedLocalHost()
    {
        if (audioStreamConnector != null)
            return audioStreamConnector.getDataSocket().getLocalAddress();

        if (videoStreamConnector != null)
            return videoStreamConnector.getDataSocket().getLocalAddress();

        NetworkAddressManagerService nam
                    = ProtocolMediaActivator.getNetworkAddressManagerService();
        InetAddress intendedDestination = getIntendedDestination(getCallPeer());

        return nam.getLocalHost(intendedDestination);
    }

    /**
     * Send empty UDP packet to target destination data/control ports
     * in order to open port on NAT or RTP proxy if any.
     *
     * @param target <tt>MediaStreamTarget</tt>
     * @param type the {@link MediaType} of the connector we'd like to send
     * the hole punching packet through.
     */
    public void sendHolePunchPacket(MediaStreamTarget target, MediaType type)
    {
        if (logger.isInfoEnabled())
            logger.info("Try to open port on NAT if any");
        try
        {
            StreamConnector connector = getStreamConnector(type);

            synchronized(connector)
            {
                /* data port (RTP) */
                connector.getDataSocket().send(new DatagramPacket(
                        new byte[0], 0, target.getDataAddress().getAddress(),
                        target.getDataAddress().getPort()));

                /* control port (RTCP) */
                connector.getControlSocket().send(new DatagramPacket(
                        new byte[0], 0, target.getControlAddress().getAddress(),
                        target.getControlAddress().getPort()));
            }
        }
        catch(Exception e)
        {
            logger.error("Error cannot send to remote peer", e);
        }
    }

    /**
     * Returns the <tt>InetAddress</tt> that is most likely to be used as a
     * next hop when contacting the specified <tt>destination</tt>. This is
     * an utility method that is used whenever we have to choose one of our
     * local addresses to put in the Via, Contact or (in the case of no
     * registrar accounts) From headers.
     *
     * @param peer the CallPeer that we would contact.
     *
     * @return the <tt>InetAddress</tt> that is most likely to be to be used
     * as a next hop when contacting the specified <tt>destination</tt>.
     *
     * @throws IllegalArgumentException if <tt>destination</tt> is not a valid
     * host/ip/fqdn
     */
    protected abstract InetAddress getIntendedDestination(U peer);

    /**
     * Returns the {@link MediaAwareCallPeer} that this transport manager is
     * serving.
     *
     * @return the {@link MediaAwareCallPeer} that this transport manager is
     * serving.
     */
    public U getCallPeer()
    {
        return callPeer;
    }
}