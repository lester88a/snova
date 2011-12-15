/**
 * 
 */
package org.snova.gae.client.connection;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.arch.buffer.Buffer;
import org.arch.common.Pair;
import org.arch.event.Event;
import org.arch.event.EventHandler;
import org.arch.event.EventHeader;
import org.arch.event.EventSegment;
import org.arch.event.http.HTTPEventContants;
import org.arch.event.http.HTTPRequestEvent;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snova.gae.client.config.GAEClientConfiguration;
import org.snova.gae.client.config.GAEClientConfiguration.GAEServerAuth;
import org.snova.gae.client.handler.ProxySession;
import org.snova.gae.client.handler.ProxySessionManager;
import org.snova.gae.client.handler.ProxySessionStatus;
import org.snova.gae.common.CompressorType;
import org.snova.gae.common.EventHeaderTags;
import org.snova.gae.common.GAEConstants;
import org.snova.gae.common.GAEEventHelper;
import org.snova.gae.common.event.AuthRequestEvent;
import org.snova.gae.common.event.AuthResponseEvent;
import org.snova.gae.common.event.CompressEvent;
import org.snova.gae.common.event.EncryptEvent;

/**
 * @author qiyingwang
 * 
 */
public abstract class ProxyConnection
{
	protected Logger	                    logger	          = LoggerFactory
	                                                                  .getLogger(getClass());
	protected static GAEClientConfiguration	cfg	              = GAEClientConfiguration
	                                                                  .getInstance();
	private LinkedList<Event>	            queuedEvents	  = new LinkedList<Event>();
	protected GAEServerAuth	                auth	          = null;
	private String	                        authToken	      = null;
	private AtomicInteger	                        authTokenLock	  = new AtomicInteger(0);
	private Set<Integer>	                relevantSessions	= new HashSet<Integer>();
	private EventHandler	                outSessionHandler	= null;
	
	protected ProxyConnection(GAEServerAuth auth)
	{
		this.auth = auth;
	}
	
	protected abstract boolean doSend(Buffer msgbuffer);
	
	protected abstract int getMaxDataPackageSize();
	
	protected void doClose()
	{
		
	}
	
	public abstract boolean isReady();
	
	protected void setAvailable(boolean flag)
	{
		// nothing
	}
	
	protected void closeRelevantSessions(HttpResponse res)
	{
		for (Integer sessionID : relevantSessions)
		{
			ProxySession session = ProxySessionManager.getInstance()
			        .getProxySession(sessionID);
			if (null != session
			        && session.getStatus().equals(
			                ProxySessionStatus.WAITING_NORMAL_RESPONSE))
			{
				session.close(res);
			}
		}
		relevantSessions.clear();
	}
	
	public void close()
	{
		doClose();
		closeRelevantSessions(null);
		authTokenLock.set(-1);
		setAuthToken((AuthResponseEvent) null);
	}
	
	public boolean auth()
	{
		if (null != authToken)
		{
			return true;
		}
		AuthRequestEvent event = new AuthRequestEvent();
		event.appid = auth.appid.trim();
		event.user = auth.user.trim();
		event.passwd = auth.passwd.trim();
		Pair<Channel, Integer> attach = new Pair<Channel, Integer>(null, 0);
		event.setAttachment(attach);
		authTokenLock.set(0);
		if (!send(event))
		{
			return false;
		}
		synchronized (authTokenLock)
		{
			try
			{
				if(authTokenLock.get() == 0)
				{
					authTokenLock.wait(60 * 1000); // 1min
				}
			}
			catch (InterruptedException e)
			{
				return false;
			}
		}
		return authToken != null && !authToken.isEmpty();
	}
	
	public String getAuthToken()
	{
		return authToken;
	}
	
	private void setAuthToken(AuthResponseEvent ev)
	{
		synchronized (authTokenLock)
		{
			if (null != ev)
			{
				setAuthToken(ev.token);
			}
			authTokenLock.notify();
		}
		
		if (null != ev)
		{
			if (logger.isInfoEnabled())
			{
				logger.info("Set connection auth token:" + ev.token);
			}
			if (null != ev.error)
			{
				logger.error("Failed to auth appid:" + ev.appid
				        + " fore reason:" + ev.error);
			}
		}
	}
	
	public void setAuthToken(String token)
	{
		authToken = token;
	}
	
	public boolean send(Event event, EventHandler handler)
	{
		outSessionHandler = handler;
		return send(event);
	}
	
	public boolean send(Event event)
	{
		Pair<Channel, Integer> attach = (Pair<Channel, Integer>) event
		        .getAttachment();
		if (!isReady())
		{
			if (logger.isDebugEnabled())
			{
				logger.debug("Connection:" + this.hashCode()
				        + " queue session[" + attach.second + "] event:");
				logger.debug(event.toString());
			}
			queuedEvents.add(event);
			return true;
		}
		setAvailable(false);
		if (logger.isDebugEnabled())
		{
			if (event instanceof HTTPRequestEvent)
			{
				logger.debug("Connection:" + this.hashCode()
				        + " send out session[" + attach.second
				        + "] HTTP request:");
				logger.debug(event.toString());
			}
		}
		if (null == attach)
		{
			attach = new Pair<Channel, Integer>(null, -1);
		}
		
		EventHeaderTags tags = new EventHeaderTags();
		// tags.compressor = cfg.getCompressor();
		// tags.encrypter = cfg.getEncrypter();
		tags.token = authToken;
		
		event.setHash(attach.second);
		CompressorType compressType = cfg.getCompressorType();
		CompressEvent comress = new CompressEvent(compressType, event);
		comress.setHash(attach.second);
		EncryptEvent enc = new EncryptEvent(cfg.getEncrypterType(), comress);
		enc.setHash(attach.second);
		relevantSessions.add(attach.second);
		Buffer msgbuffer = GAEEventHelper.encodeEvent(tags, enc);
		if (msgbuffer.readableBytes() > getMaxDataPackageSize())
		{
			Buffer[] segments = GAEEventHelper.splitEventBuffer(msgbuffer,
			        enc.getHash(), getMaxDataPackageSize(), tags);
			for (int i = 0; i < segments.length; i++)
			{
				doSend(segments[i]);
			}
			return true;
		}
		else
		{
			return doSend(msgbuffer);
		}
	}
	
	private void handleRecvEvent(Event ev)
	{
		if (null == ev)
		{
			close();
			return;
		}
		
		relevantSessions.remove(ev.getHash());
		int type;
		type = Event.getTypeVersion(ev.getClass()).type;
		if (logger.isDebugEnabled())
		{
			logger.debug("Handle received session[" + ev.getHash()
			        + "] response event[" + type + "]");
		}
		switch (type)
		{
			case GAEConstants.AUTH_RESPONSE_EVENT_TYPE:
			{
				setAuthToken((AuthResponseEvent) ev);
				return;
			}
			case GAEConstants.COMPRESS_EVENT_TYPE:
			{
				handleRecvEvent(((CompressEvent) ev).ev);
				return;
			}
			case GAEConstants.ENCRYPT_EVENT_TYPE:
			{
				handleRecvEvent(((EncryptEvent) ev).ev);
				return;
			}
			case Event.RESERVED_SEGMENT_EVENT_TYPE:
			{
				EventSegment segment = (EventSegment) ev;
				Buffer evntContent = GAEEventHelper.mergeEventSegment(segment,
				        null);
				if (null != evntContent)
				{
					doRecv(evntContent);
				}
				return;
			}
			case HTTPEventContants.HTTP_RESPONSE_EVENT_TYPE:
			{
				// just let
				if (logger.isDebugEnabled())
				{
					logger.debug("Proxy connection received HTTP response:");
					logger.debug(ev.toString());
				}
				break;
			}
			case GAEConstants.ADMIN_RESPONSE_EVENT_TYPE:
			case GAEConstants.GROUOP_LIST_RESPONSE_EVENT_TYPE:
			case GAEConstants.USER_LIST_RESPONSE_EVENT_TYPE:
			{
				break;
			}
			default:
			{
				logger.error("Unsupported event type:" + type
				        + " for proxy connection");
				break;
			}
		}
		
		ProxySession session = ProxySessionManager.getInstance()
		        .getProxySession(ev.getHash());
		if (null != session)
		{
			session.handleResponse(ev);
			// session.close();
		}
		else
		{
			if (null != outSessionHandler)
			{
				EventHeader header = new EventHeader();
				header.type = Event.getTypeVersion(ev.getClass()).type;
				header.version = Event.getTypeVersion(ev.getClass()).version;
				header.hash = ev.getHash();
				// header.type = Event.getTypeVersion(ev.getClass())
				outSessionHandler.onEvent(header, ev);
			}
			else
			{
				logger.error("Failed o find session or handle to handle received session["
				        + ev.getHash() + "] response event.");
			}
		}
	}
	
	protected void doRecv(Buffer content)
	{
		Event ev = null;
		try
		{
			ev = GAEEventHelper.parseEvent(content);
		}
		catch (Exception e)
		{
			logger.error("Failed to parse event.", e);
			return;
		}
		handleRecvEvent(ev);
		if (!queuedEvents.isEmpty())
		{
			if (isReady())
			{
				Event qe = queuedEvents.removeFirst();
				send(qe);
			}
		}
	}
}