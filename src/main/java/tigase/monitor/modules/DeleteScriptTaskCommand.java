package tigase.monitor.modules;

import java.util.Collection;

import tigase.component.adhoc.AdHocCommand;
import tigase.component.adhoc.AdHocCommandException;
import tigase.component.adhoc.AdHocResponse;
import tigase.component.adhoc.AdhHocRequest;
import tigase.form.Field;
import tigase.form.Form;
import tigase.kernel.beans.Inject;
import tigase.kernel.core.Kernel;
import tigase.monitor.MonitorTask;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.JID;

public class DeleteScriptTaskCommand implements AdHocCommand {

	public static final String ID = "x-delete-task";

	@Inject
	private Kernel kernel;

	@Override
	public void execute(AdhHocRequest request, AdHocResponse response) throws AdHocCommandException {
		try {
			final Element data = request.getCommand().getChild("x", "jabber:x:data");

			if (request.getAction() != null && "cancel".equals(request.getAction())) {
				response.cancelSession();
			} else if (data == null) {
				Form form = new Form("form", "Delete monitor task", null);

				Collection<String> taskNames = kernel.getNamesOf(MonitorTask.class);

				form.addField(Field.fieldListSingle("delete_task", "", "Task to delete", taskNames.toArray(new String[] {}),
						taskNames.toArray(new String[] {})));

				response.getElements().add(form.getElement());
				response.startSession();
			} else {
				Form form = new Form(data);

				if ("submit".equals(form.getType())) {
					String taskName = form.getAsString("delete_task");

					Object i = kernel.getInstance(taskName);
					if (i instanceof MonitorTask)
						kernel.unregister(taskName);
					else
						throw new RuntimeException("Are you kidding me?");
				}

				response.completeSession();
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw new AdHocCommandException(Authorization.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	public Kernel getKernel() {
		return kernel;
	}

	@Override
	public String getName() {
		return "Delete monitor task";
	}

	@Override
	public String getNode() {
		return ID;
	}

	@Override
	public boolean isAllowedFor(JID jid) {
		return true;
	}

	public void setKernel(Kernel kernel) {
		this.kernel = kernel;
	}

}
