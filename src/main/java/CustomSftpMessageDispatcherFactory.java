import org.mule.api.MuleException;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.transport.MessageDispatcher;
import org.mule.transport.ftp.FtpMessageDispatcherFactory;


public class CustomSftpMessageDispatcherFactory extends
		FtpMessageDispatcherFactory {
	public MessageDispatcher create(OutboundEndpoint endpoint) throws MuleException
    {
        return new CustomSftpMessageDispatcher(endpoint);
    }
}
