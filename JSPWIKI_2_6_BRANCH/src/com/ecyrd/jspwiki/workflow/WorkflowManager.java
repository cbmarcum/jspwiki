/* 
    JSPWiki - a JSP-based WikiWiki clone.

    Copyright (C) 2001-2002 Janne Jalkanen (Janne.Jalkanen@iki.fi)

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 2.1 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.ecyrd.jspwiki.workflow;

import java.security.Principal;
import java.util.*;

import com.ecyrd.jspwiki.WikiEngine;
import com.ecyrd.jspwiki.WikiException;
import com.ecyrd.jspwiki.WikiSession;
import com.ecyrd.jspwiki.auth.acl.UnresolvedPrincipal;
import com.ecyrd.jspwiki.event.WikiEvent;
import com.ecyrd.jspwiki.event.WikiEventListener;
import com.ecyrd.jspwiki.event.WorkflowEvent;

/**
 * <p>
 * Monitor class that tracks running Workflows. The WorkflowManager also keeps
 * track of the names of users or groups expected to approve particular
 * Workflows.
 * </p>
 *
 * @author Andrew Jaquith
 */
public class WorkflowManager implements WikiEventListener
{

    private final DecisionQueue m_queue = new DecisionQueue();

    private final Set m_workflows;

    private final Map m_approvers;

    private final List m_completed;

    /** The prefix to use for looking up <code>jspwiki.properties</code> approval roles. */
    protected static final String PROPERTY_APPROVER_PREFIX = "jspwiki.approver.";

    /**
     * Constructs a new WorkflowManager, with an empty workflow cache. New
     * Workflows are automatically assigned unique identifiers, starting with 1.
     */
    public WorkflowManager()
    {
        m_next = 1;
        m_workflows = new HashSet();
        m_approvers = new HashMap();
        m_completed = new ArrayList();
    }

    /**
     * Adds a new workflow to the set of workflows and starts it. The new
     * workflow is automatically assigned a unique ID. If another workflow with
     * the same ID already exists, this method throws a WikIException.
     * @param workflow the workflow to start
     * @throws WikiException if a workflow the automatically assigned
     * ID already exist; this should not happen normally
     */
    public void start( Workflow workflow ) throws WikiException
    {
        m_workflows.add( workflow );
        workflow.setWorkflowManager( this );
        workflow.setId( nextId() );
        workflow.start();
    }

    /**
     * Returns a collection of the currently active workflows.
     *
     * @return the current workflows
     */
    public Collection getWorkflows()
    {
        return new HashSet( m_workflows );
    }

    /**
     * Returns a collection of finished workflows; that is, those that have aborted or completed.
     * @return the finished workflows
     */
    public List getCompletedWorkflows()
    {
        return new ArrayList( m_completed );
    }

    private WikiEngine m_engine = null;

    /**
     * Initializes the WorkflowManager using a specfied WikiEngine and
     * properties. Any properties that begin with
     * {@link #PROPERTY_APPROVER_PREFIX} will be assumed to be
     * Decisions that require approval. For a given property key, everything
     * after the prefix denotes the Decision's message key. The property
     * value indicates the Principal (Role, GroupPrincipal, WikiPrincipal) that
     * must approve the Decision. For example, if the property key/value pair
     * is <code>jspwiki.approver.workflow.saveWikiPage=Admin</code>,
     * the Decision's message key is <code>workflow.saveWikiPage</code>.
     * The Principal <code>Admin</code> will be resolved via
     * {@link com.ecyrd.jspwiki.auth.AuthorizationManager#resolvePrincipal(String)}.
     * @param engine the wiki engine to associate with this WorkflowManager
     * @param props the wiki engine's properties
     */
    public void initialize( WikiEngine engine, Properties props )
    {
        m_engine = engine;

        // Identify the workflows requiring approvals
        for ( Iterator it = props.keySet().iterator(); it.hasNext(); )
        {
            String prop = (String) it.next();
            if ( prop.startsWith( PROPERTY_APPROVER_PREFIX ) )
            {

                // For the key, everything after the prefix is the workflow name
                String key = prop.substring( PROPERTY_APPROVER_PREFIX.length() );
                if ( key != null && key.length() > 0 )
                {

                    // Only use non-null/non-blank approvers
                    String approver = props.getProperty( prop );
                    if ( approver != null && approver.length() > 0 )
                    {
                        m_approvers.put( key, new UnresolvedPrincipal( approver ) );
                    }
                }
            }
        }
    }

    /**
     * Returns <code>true</code> if a workflow matching a particular key
     * contains an approval step.
     *
     * @param messageKey
     *            the name of the workflow; corresponds to the value returned by
     *            {@link Workflow#getMessageKey()}.
     * @return the result
     */
    public boolean requiresApproval( String messageKey )
    {
        return  m_approvers.containsKey( messageKey );
    }

    /**
     * Looks up and resolves the actor who approves a Decision for a particular
     * Workflow, based on the Workflow's message key. If not found, or if
     * Principal is Unresolved, throws WikiException. This particular
     * implementation always returns the GroupPrincipal <code>Admin</code>
     *
     * @param messageKey the Decision's message key
     * @return the actor who approves Decisions
     * @throws WikiException if the message key was not found, or the
     * Principal value corresponding to the key could not be resolved
     */
    public Principal getApprover( String messageKey ) throws WikiException
    {
        Principal approver = (Principal) m_approvers.get( messageKey );
        if ( approver == null )
        {
            throw new WikiException( "Workflow '" + messageKey + "' does not require approval." );
        }

        // Try to resolve UnresolvedPrincipals
        if ( approver instanceof UnresolvedPrincipal )
        {
            String name = approver.getName();
            approver = m_engine.getAuthorizationManager().resolvePrincipal( name );

            // If still unresolved, throw exception; otherwise, freshen our
            // cache
            if ( approver instanceof UnresolvedPrincipal )
            {
                throw new WikiException( "Workflow approver '" + name + "' cannot not be resolved." );
            }

            m_approvers.put( messageKey, approver );
        }
        return approver;
    }

    /**
     * Protected helper method that returns the associated WikiEngine
     *
     * @return the wiki engine
     */
    protected WikiEngine getEngine()
    {
        if ( m_engine == null )
        {
            throw new IllegalStateException( "WikiEngine cannot be null; please initialize WorkflowManager first." );
        }
        return m_engine;
    }

    /**
     * Returns the DecisionQueue associated with this WorkflowManager
     *
     * @return the decision queue
     */
    public DecisionQueue getDecisionQueue()
    {
        return m_queue;
    }

    private volatile int m_next;

    /**
     * Returns the next available unique identifier, which is subsequently
     * incremented.
     *
     * @return the id
     */
    private synchronized int nextId()
    {
        int current = m_next;
        m_next++;
        return current;
    }

    /**
     * Returns the current workflows a wiki session owns. These are workflows whose
     * {@link Workflow#getOwner()} method returns a Principal also possessed by the
     * wiki session (see {@link com.ecyrd.jspwiki.WikiSession#getPrincipals()}). If the
     * wiki session is not authenticated, this method returns an empty Collection.
     * @param session the wiki session
     * @return the collection workflows the wiki session owns, which may be empty
     */
    public Collection getOwnerWorkflows(WikiSession session)
    {
        List workflows = new ArrayList();
        if ( session.isAuthenticated() )
        {
            Principal[] sessionPrincipals = session.getPrincipals();
            for ( Iterator it = m_workflows.iterator(); it.hasNext(); )
            {
                Workflow w = (Workflow)it.next();
                Principal owner = w.getOwner();
                for ( int i = 0; i < sessionPrincipals.length; i++ )
                {
                    if ( sessionPrincipals[i].equals(owner) )
                    {
                        workflows.add( w );
                        break;
                    }
                }
            }
        }
        return workflows;
    }

    /**
     * Listens for {@link com.ecyrd.jspwiki.event.WorkflowEvent} objects emitted
     * by Workflows. In particular, this method listens for
     * {@link com.ecyrd.jspwiki.event.WorkflowEvent#CREATED},
     * {@link com.ecyrd.jspwiki.event.WorkflowEvent#ABORTED} and
     * {@link com.ecyrd.jspwiki.event.WorkflowEvent#COMPLETED} events. If a
     * workflow is created, it is automatically added to the cache. If one is
     * aborted or completed, it is automatically removed.
     * @param event the event passed to this listener
     */
    public void actionPerformed(WikiEvent event)
    {
        if (event instanceof WorkflowEvent)
        {
            Workflow workflow = (Workflow) event.getSource();
            switch ( event.getType() )
            {
                case WorkflowEvent.ABORTED:
                    // Remove from manager
                    remove( workflow );
                    break;
                case WorkflowEvent.COMPLETED:
                    // Remove from manager
                    remove( workflow );
                    break;
                case WorkflowEvent.CREATED:
                    // Add to manager
                    add( workflow );
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Protected helper method that adds a newly created Workflow to the cache,
     * and sets its <code>workflowManager</code> and <code>Id</code>
     * properties if not set.
     *
     * @param workflow
     *            the workflow to add
     */
    protected synchronized void add( Workflow workflow )
    {
        if ( workflow.getWorkflowManager() == null )
        {
            workflow.setWorkflowManager( this );
        }
        if ( workflow.getId() == Workflow.ID_NOT_SET )
        {
            workflow.setId( nextId() );
        }
        m_workflows.add( workflow );
    }

    /**
     * Protected helper method that removes a specified Workflow from the cache,
     * and moves it to the workflow history list. This method defensively
     * checks to see if the workflow has not yet been removed.
     *
     * @param workflow
     *            the workflow to remove
     */
    protected synchronized void remove(Workflow workflow)
    {
        if ( m_workflows.contains( workflow ) )
        {
            m_workflows.remove( workflow );
            m_completed.add( workflow );
        }
    }
}