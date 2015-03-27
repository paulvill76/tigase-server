package tigase.monitor.modules;

import java.util.ArrayList;
import java.util.List;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import tigase.component.adhoc.AdHocCommand;
import tigase.component.adhoc.AdHocCommandException;
import tigase.component.adhoc.AdHocResponse;
import tigase.component.adhoc.AdhHocRequest;
import tigase.form.Field;
import tigase.form.Form;
import tigase.kernel.beans.Inject;
import tigase.monitor.TasksScriptRegistrar;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.JID;

public class AddScriptTaskCommand implements AdHocCommand {

	public static final String ID = "x-add-task";

	@Inject
	private ScriptEngineManager scriptEngineManager;

	@Inject
	private TasksScriptRegistrar tasksScriptRegistrar;

	@Override
	public void execute(AdhHocRequest request, AdHocResponse response) throws AdHocCommandException {
		try {
			final Element data = request.getCommand().getChild("x", "jabber:x:data");

			if (request.getAction() != null && "cancel".equals(request.getAction())) {
				response.cancelSession();
			} else if (data == null) {
				Form form = new Form("form", "Add monitor script", null);

				List<ScriptEngineFactory> sef = scriptEngineManager.getEngineFactories();
				ArrayList<String> labels = new ArrayList<String>();
				ArrayList<String> values = new ArrayList<String>();
				for (ScriptEngineFactory scriptEngineFactory : sef) {
					labels.add(scriptEngineFactory.getLanguageName());
					values.add(scriptEngineFactory.getExtensions().get(0));
				}

				form.addField(Field.fieldTextSingle("scriptName", "", "Script name"));
				form.addField(Field.fieldListSingle("scriptExtension", "", "Script engine", labels.toArray(new String[] {}),
						values.toArray(new String[] {})));
				form.addField(Field.fieldTextMulti("scriptContent", "", "Script"));

				response.getElements().add(form.getElement());
				response.startSession();
			} else {
				Form form = new Form(data);

				if ("submit".equals(form.getType())) {
					String scriptName = form.getAsString("scriptName");
					String scriptExtension = form.getAsString("scriptExtension");
					String scriptContent = form.getAsString("scriptContent");

					tasksScriptRegistrar.registerScript(scriptName, scriptExtension, scriptContent);

				}

				response.completeSession();
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw new AdHocCommandException(Authorization.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	@Override
	public String getName() {
		return "Add monitor task";
	}

	@Override
	public String getNode() {
		return ID;
	}

	public ScriptEngineManager getScriptEngineManager() {
		return scriptEngineManager;
	}

	public TasksScriptRegistrar getTasksScriptRegistrar() {
		return tasksScriptRegistrar;
	}

	@Override
	public boolean isAllowedFor(JID jid) {
		return true;
	}

	public void setScriptEngineManager(ScriptEngineManager scriptEngineManager) {
		this.scriptEngineManager = scriptEngineManager;
	}

	public void setTasksScriptRegistrar(TasksScriptRegistrar tasksScriptRegistrar) {
		this.tasksScriptRegistrar = tasksScriptRegistrar;
	}

}
