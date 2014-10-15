import org.mule.api.MuleEvent;
import org.mule.api.MuleMessage;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.transport.sftp.SftpClient;
import org.mule.transport.sftp.SftpConnectionFactory;
import org.mule.transport.sftp.SftpConnector;
import org.mule.transport.sftp.SftpMessageDispatcher;
import org.mule.transport.sftp.SftpUtil;
import org.mule.transport.sftp.notification.SftpNotifier;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 
 * @author Anton Kupias
 * 
 * SftpMessageDispatcher-luokan kopio, johon on lisätty tuki puuttuvan (ali-)hakemiston luonnille
 * 
 * huom: append-option osuus kommentoitu pois koska psop-käytössä on vanha versio ilman append-optiota. Muleversion päivityksen yhteydessä voi poistaa kommentoinnin ja ottaa käyttöön jos tarvii
 *
 */
public class CustomSftpMessageDispatcher extends SftpMessageDispatcher {
	
	private SftpConnector connector;
    private SftpUtil sftpUtil = null;
    
	 public CustomSftpMessageDispatcher(OutboundEndpoint endpoint)
	 {
	  super(endpoint);
      connector = (SftpConnector) endpoint.getConnector();
      sftpUtil = new SftpUtil(endpoint);
	 }

	 protected void doDispatch(MuleEvent event) throws Exception
	 {
		 String filename = buildFilename(event);
	        InputStream inputStream = generateInputStream(event);
	        
	        SftpClient client = null;
	        boolean useTempDir = false;
	        String transferFilename = null;

	        try
	        {
	        	String serviceName = (event.getFlowConstruct() == null)
	                                 ? "UNKNOWN SERVICE"
	                                 : event.getFlowConstruct().getName();
	            SftpNotifier notifier = new SftpNotifier(connector, event.getMessage(), endpoint, serviceName);
	            String destDir = endpoint.getEndpointURI().getPath();
	            
	            //tästä lähtee muokkaus:
	            //tässä luodaan directory jos ei löydy. connector.createSftpClient failaa ilman sitä, joten semmosta ei voi myöskään käyttää luomiseen.
	            client = SftpConnectionFactory.createClient(endpoint, connector.getPreferredAuthenticationMethods());
	            try { 
	            	client.changeWorkingDirectory(destDir);
	            }
	            catch(IOException e) {
	            	logger.error("...never mind that, we have a backup method to create it.");
	            	/** 
	            	Sessiosta voidaan avata komentokanava mkdir-komentoa varten, luokka löytyy samasta paketista kuin Mulen käyttämä sftp-toteutus
	            	http://epaul.github.io/jsch-documentation/javadoc/com/jcraft/jsch/ChannelExec.html
	            	*/
	            	Channel channel = client.getChannelSftp().getSession().openChannel("exec");
	                ((ChannelExec)channel).setCommand("mkdir -p "+destDir);
	                channel.setInputStream(null);
	                ((ChannelExec)channel).setErrStream(System.err);
	                
	                channel.connect();

	                /** jos haluaa tulostella jotain...
	                 * toteutuksen malli on peräisin täältä: http://www.jcraft.com/jsch/examples/Exec.java
	                InputStream in=channel.getInputStream();
	                byte[] tmp=new byte[1024];
	                while(true){
	                  while(in.available()>0){
	                    int i=in.read(tmp, 0, 1024);
	                    if(i<0)break;
	                    System.out.print(new String(tmp, 0, i));
	                  }
	                  if(channel.isClosed()){
	                    if(in.available()>0) continue;
	                    System.out.println("exit-status: "+channel.getExitStatus());
	                    break;
	                  }
	                  try{Thread.sleep(1000);}catch(Exception ee){}
	                }
	                */
	                channel.disconnect();
	            }
	            connector.releaseClient(endpoint, client);
	            //homma ohi ja jatketaan parent-luokan koodilla
	            
	            client = connector.createSftpClient(endpoint, notifier);
	            
	            
	            if (logger.isDebugEnabled())
	            {
	                logger.debug("Connection setup successful, writing file.");
	            }

	            // Duplicate Handling
	            filename = client.duplicateHandling(destDir, filename, sftpUtil.getDuplicateHandling());
	            transferFilename = filename;

	            useTempDir = sftpUtil.isUseTempDirOutbound();
	            if (useTempDir)
	            {
	                // TODO move to a init-method like doConnect?
	                // cd to tempDir and create it if it doesn't already exist
	                sftpUtil.cwdToTempDirOnOutbound(client, destDir);

	                // Add unique file-name (if configured) for use during transfer to
	                // temp-dir
	                boolean addUniqueSuffix = sftpUtil.isUseTempFileTimestampSuffix();
	                if (addUniqueSuffix)
	                {
	                    transferFilename = sftpUtil.createUniqueSuffix(transferFilename);
	                }
	            }
                //append on siis vasta uudemmissa Mule-versioissa
	            // send file over sftp
	            // choose appropriate writing mode
	            /**
	            if (sftpUtil.getDuplicateHandling().equals(SftpConnector.PROPERTY_DUPLICATE_HANDLING_APPEND))
	            {
	                client.storeFile(transferFilename, inputStream, SftpClient.WriteMode.APPEND);
	            }
	            else
	            {
	                client.storeFile(transferFilename, inputStream);
	            }
                */
	            client.storeFile(transferFilename, inputStream);
	            if (useTempDir)
	            {
	                // Move the file to its final destination
	                client.rename(transferFilename, destDir + "/" + filename);
	            }

	            logger.info("Successfully wrote file '" + filename + "' to " + endpoint.getEndpointURI());
	        }
	        catch (Exception e)
	        {
	            logger.error("Unexpected exception attempting to write file, message was: " + e.getMessage(), e);

	            sftpUtil.setErrorOccurredOnInputStream(inputStream);

	            if (useTempDir)
	            {
	                // Cleanup the remote temp dir from the not fullt completely
	                // transferred file!
	                String tempDir = sftpUtil.getTempDirOutbound();
	                sftpUtil.cleanupTempDir(client, transferFilename, tempDir);
	            }
	            throw e;
	        }
	        finally
	        {
	            if (client != null)
	            {
	                // If the connection fails, the client will be null, otherwise
	                // disconnect.
	                connector.releaseClient(endpoint, client);
	            }

	            inputStream.close();
	        }
	 }
	 
	 private InputStream generateInputStream(MuleEvent event)
	    {
	        Object data = event.getMessage().getPayload();
	        // byte[], String, or InputStream payloads supported.

	        byte[] buf;
	        InputStream inputStream;

	        if (data instanceof byte[])
	        {
	            buf = (byte[]) data;
	            inputStream = new ByteArrayInputStream(buf);
	        }
	        else if (data instanceof InputStream)
	        {
	            inputStream = (InputStream) data;
	        }
	        else if (data instanceof String)
	        {
	            inputStream = new ByteArrayInputStream(((String) data).getBytes());
	        }
	        else
	        {
	            throw new IllegalArgumentException(
	                    "Unexpected message type: java.io.InputStream, byte[], or String expected. Got "
	                    + data.getClass().getName());
	        }
	        return inputStream;
	    }

	    private String buildFilename(MuleEvent event)
	    {
	        MuleMessage muleMessage = event.getMessage();
	        String outPattern = (String) endpoint.getProperty(SftpConnector.PROPERTY_OUTPUT_PATTERN);
	        if (outPattern == null)
	        {
	            outPattern = (String) muleMessage.getProperty(SftpConnector.PROPERTY_OUTPUT_PATTERN,
	                                                          connector.getOutputPattern());
	        }
	        String filename = connector.getFilenameParser().getFilename(muleMessage, outPattern);
	        if (filename == null)
	        {
	            filename = (String) event.getMessage().findPropertyInAnyScope(SftpConnector.PROPERTY_FILENAME,
	                                                                          null);
	        }
	        return filename;
	    }

}
