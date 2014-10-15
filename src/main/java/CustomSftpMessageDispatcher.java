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
 * SftpMessageDispatcher fork with support for creating a non-existing remote path
 * https://github.com/mulesoft/mule/blob/mule-3.4.x/transports/sftp/src/main/java/org/mule/transport/sftp/SftpMessageDispatcher.java
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
	            
	            //modifications start here -Anton
	            //create the directory if we get an exception. connector.createSftpClient fails with a non-existing path, thus we can not use that to create the client either
	            client = SftpConnectionFactory.createClient(endpoint, connector.getPreferredAuthenticationMethods());
	            try { 
	            	client.changeWorkingDirectory(destDir);
	            }
	            catch(IOException e) {
                        //an error message was just printed, but we know it's ok   
	            	logger.error("...never mind that, we have a backup method to create it.");
	            	/** 
	            	We can use the session to open a Channel for sending a remote mkdir command. Mule uses this library for SFTP.
                        http://epaul.github.io/jsch-documentation/javadoc/com/jcraft/jsch/ChannelExec.html
	            	*/
	            	Channel channel = client.getChannelSftp().getSession().openChannel("exec");
	                ((ChannelExec)channel).setCommand("mkdir -p "+destDir);
	                channel.setInputStream(null);
	                ((ChannelExec)channel).setErrStream(System.err);
	                
	                channel.connect();

	                /** in case you want to print some info...
	                 * this is from a JSch example: http://www.jcraft.com/jsch/examples/Exec.java
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
                    //we're done with our custom stuff, so let's continue with the original Mule code
	            
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
                    //comment out the "if" block to use this class with older versions of Mule. SFTP Append mode is a later addition.
	            // send file over sftp
	            // choose appropriate writing mode
	            if (sftpUtil.getDuplicateHandling().equals(SftpConnector.PROPERTY_DUPLICATE_HANDLING_APPEND))
	            {
	                client.storeFile(transferFilename, inputStream, SftpClient.WriteMode.APPEND);
	            }
	            else
	            {
	                client.storeFile(transferFilename, inputStream);
	            }
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
