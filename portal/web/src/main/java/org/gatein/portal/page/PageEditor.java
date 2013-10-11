/*
 * Copyright (C) 2013 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.gatein.portal.page;

import javax.inject.Inject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import juzu.Param;
import juzu.Resource;
import juzu.Response;
import juzu.Route;
import juzu.impl.common.JSON;
import juzu.impl.common.Tools;
import juzu.request.ResourceContext;

import org.exoplatform.portal.config.model.ApplicationState;
import org.exoplatform.portal.pom.data.ApplicationData;
import org.exoplatform.portal.pom.data.ComponentData;
import org.exoplatform.portal.pom.data.ContainerAdapter;
import org.exoplatform.portal.pom.data.ContainerData;
import org.gatein.portal.layout.ZoneLayout;
import org.gatein.portal.layout.ZoneLayoutFactory;
import org.gatein.portal.mop.Properties;
import org.gatein.portal.mop.customization.CustomizationService;
import org.gatein.portal.mop.hierarchy.GenericScope;
import org.gatein.portal.mop.hierarchy.NodeContext;
import org.gatein.portal.mop.layout.ElementState;
import org.gatein.portal.mop.layout.LayoutService;
import org.gatein.portal.mop.navigation.NavigationContext;
import org.gatein.portal.mop.navigation.NavigationService;
import org.gatein.portal.mop.navigation.NodeState;
import org.gatein.portal.mop.page.PageKey;
import org.gatein.portal.mop.page.PageService;
import org.gatein.portal.mop.site.SiteKey;
import org.gatein.portal.page.spi.portlet.PortletContentProvider;

public class PageEditor {

    @Inject
    NavigationService navigationService;

    @Inject
    PageService pageService;

    @Inject
    LayoutService layoutService;

    @Inject
    CustomizationService customizationService;

    @Inject
    PortletContentProvider contentProvider;

    @Inject
    ZoneLayoutFactory layoutFactory;

    @Resource
    @Route(value = "/switchto/{javax.portlet.z}")
    public Response switchLayout(@Param(name = "javax.portlet.z") String id) throws Exception {
        ZoneLayout layout = (ZoneLayout) layoutFactory.builder(id).build();
        StringBuilder sb = new StringBuilder();
        layout.render(Collections.<String, Result.Fragment>emptyMap(), null, null, sb);

        JSON result = new JSON();
        result.set("code", 200);
        result.set("status", "success");

        JSON data = new JSON();
        data.set("layout_id", id);
        data.set("html", sb.toString());

        result.set("data", data);

        return Response.status(200).body(result.toString());
    }

    @Resource
    @Route(value = "/{javax.portlet.path}", priority = 2)
    public Response edit(ResourceContext context, @Param(name = "javax.portlet.path", pattern = ".*") String path) {
        NodeContext<ComponentData, ElementState> pageStructure = null;
        org.gatein.portal.mop.page.PageContext page = null;

        // Parse path
        List<String> names = new ArrayList<String>();
        for (String name : Tools.split(path, '/')) {
            if (name.length() > 0) {
                names.add(name);
            }
        }

        //
        NavigationContext navigation = navigationService.loadNavigation(SiteKey.portal("classic"));
        NodeContext<?, NodeState> root = navigationService.loadNode(NodeState.model(), navigation,
                GenericScope.branchShape(names), null);
        if (root != null) {

            // Get our node from the navigation
            NodeContext<?, NodeState> current = root;
            for (String name : names) {
                current = current.get(name);
                if (current == null) {
                    break;
                }
            }

            if (current != null) {
                // Load page windows
                NodeState state = current.getState();
                PageKey pageKey = state.getPageRef();
                page = pageService.loadPage(pageKey);
                pageStructure = (NodeContext<ComponentData, ElementState>) layoutService.loadLayout(ElementState.model(),
                        page.getLayoutId(), null);
            }
        }

        JSON result = new JSON();
        if (pageStructure != null) {
            try {
                JSON action = getAction(context);
                ContainerData rootContainer = this.buildComponentData(pageStructure);

                JSON[] childrens = action.getArray("childrens", JSON.class);
                for (JSON j : childrens) {
                    this.buildComponentTree(pageStructure, j, rootContainer);
                }

                layoutService.saveLayout(new ContainerAdapter(rootContainer), rootContainer, pageStructure, null);

                //Update layout
                String layoutId = action.getString("layout_id");
                if (layoutId != null && !layoutId.isEmpty()) {
                    page.setState(page.getState().builder().factoryId(layoutId).build());
                    pageService.savePage(page);
                }


                result.set("code", 200);
                result.set("status", "success");
                result.set("message", "OK");
            } catch (Exception ex) {
                result.set("code", 400);
                result.set("status", "error");
                result.set("message", "can not edit");
                result.set("data", Collections.EMPTY_MAP);
            }
        }

        return Response.status(200).body(result.toString()).withCharset(Charset.forName("UTF-8"))
                .withMimeType("application/json");
    }

    private void buildComponentTree(NodeContext<ComponentData, ElementState> context, JSON json, ContainerData parent) {
        String id = json.getString("id");
        String type = json.getString("type");

        NodeContext<ComponentData, ElementState> ctx = this.find(id, context);

        ComponentData componentData = null;
        if (ctx == null) {
            if ("container".equalsIgnoreCase(type)) {
                componentData = new ContainerData(null, id, null, null, null, null, null, null, null, null, null, new ArrayList<String>(), new LinkedList<ComponentData>());
            } else if ("application".equalsIgnoreCase(type)) {
                //TODO: process when add new portlet
            }
        } else {
            componentData = this.buildComponentData(ctx);
        }

        if (componentData == null) {
            return;
        }

        if (parent != null) {
            parent.getChildren().add(componentData);
        }

        //Process children of this node
        JSON[] childrens = json.getArray("childrens", JSON.class);
        for (JSON j : childrens) {
            this.buildComponentTree(context, j, (ContainerData) componentData);
        }
    }

    private <T extends ComponentData> T buildComponentData(NodeContext<ComponentData, ElementState> context) {
        ElementState state = context.getState();
        if (state instanceof ElementState.Container) {
            ElementState.Container containerState = (ElementState.Container) state;
            Properties properties = containerState.properties;
            ContainerData containerData = new ContainerData(context.getId(), context.getName(), context.getId(),
                    properties.get(ElementState.Container.NAME), properties.get(ElementState.Container.ICON),
                    properties.get(ElementState.Container.TEMPLATE), properties.get(ElementState.Container.FACTORY_ID),
                    properties.get(ElementState.Container.TITLE), properties.get(ElementState.Container.DESCRIPTION),
                    properties.get(ElementState.Container.WIDTH), properties.get(ElementState.Container.HEIGHT),
                    containerState.getAccessPermissions(), new ArrayList<ComponentData>());

            return (T) containerData;
        } else if (state instanceof ElementState.Window) {
            ElementState.Window winState = (ElementState.Window) state;
            Properties properties = winState.properties;
            ApplicationState appState = winState.state;
            ApplicationData appData = new ApplicationData(context.getId(), context.getName(), winState.type, appState,
                    context.getId(), properties.get(ElementState.Window.TITLE), properties.get(ElementState.Window.ICON),
                    properties.get(ElementState.Window.DESCRIPTION), properties.get(ElementState.Window.SHOW_INFO_BAR),
                    properties.get(ElementState.Window.SHOW_APPLICATION_STATE),
                    properties.get(ElementState.Window.SHOW_APPLICATION_MODE), properties.get(ElementState.Window.THEME),
                    properties.get(ElementState.Window.WIDTH), properties.get(ElementState.Window.HEIGHT),
                    Collections.EMPTY_MAP, winState.accessPermissions);

            return (T) appData;
        }
        return null;
    }

    private NodeContext<ComponentData, ElementState> find(String id, NodeContext<ComponentData, ElementState> target) {
        if (target.getName().equals(id)) {
            return target;
        } else {
            for (NodeContext<ComponentData, ElementState> child : target) {
                NodeContext<ComponentData, ElementState> tmp = this.find(id, child);
                if (tmp != null) {
                    return tmp;
                }
            }
        }
        return null;
    }

    private JSON getAction(ResourceContext context) throws Exception {
        InputStream content = context.getClientContext().getInputStream();

        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            if (content != null) {
                bufferedReader = new BufferedReader(new InputStreamReader(content));
                char[] charBuffer = new char[128];
                int bytesRead = -1;
                while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                    stringBuilder.append(charBuffer, 0, bytesRead);
                }
            }
        } finally {
            if (bufferedReader != null) {
                bufferedReader.close();
            }
        }
        String body = stringBuilder.toString();
        return (JSON) JSON.parse(body);
    }
}
