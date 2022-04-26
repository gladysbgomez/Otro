package engine;

import gamelogic.World;
import gamelogic.Asteroide;
import gamelogic.Moneda;
import gamelogic.NaveNeutra;
import gamelogic.NavePlayer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.dyn4j.geometry.Vector2;

public class Game implements Runnable {

    private LinkedList<State> states;
    private LinkedList<StaticState> staticStates;
    private HashMap<String, LinkedList<Action>> actions;
    private ConcurrentHashMap<String, HashMap<String, JSONObject>> actionsSended; //sessionid -> (actionName, actionJSON)
    private HashMap<String, GameView> gameViews;
    private ConcurrentHashMap<String, String> gameViewsSended; //sessionid -> [enter, leave]
    private Phaser viewsBarrier;
    private String gameState;
    private String gameFullState;
    private String gameStaticState;
    private boolean endGame;

    private Lobby lobby;
    private int width=1366;
    private int height=639;

    //private int width=800;
    //private int height=600;

    //distancia máxima de detección
    double distancia_max = 60;//100; 30; //15;//0.8;
    
    //para determinar, en porcentajes, la cercanía del robot a un objeto detectado
    int porcentaje=100;
    
    int coeficiente=10000;//100 //10000
    
    //es el ángulo que se suma y se resta al ángulo del robot para formar el cono de detección de objetos
    double angulo_cono = 0.5; // 0.5 //1
    
    //valor de la concentración del color cuando se detecta un objeto del color solicitado frente al robot referencia
    double concentracion_frontal = 80;
    
    int concentracion_max=160;
    
    //String concentracion="";
    
    //constructor
    public Game(Lobby lobby) {
        this.states = new LinkedList<>();
        this.staticStates = new LinkedList<>();
        this.actions = new HashMap<>();
        this.actionsSended = new ConcurrentHashMap();
        this.gameViews = new HashMap<>();
        this.gameViewsSended = new ConcurrentHashMap<>();
        this.viewsBarrier = new Phaser(1);
        this.endGame = false;
        this.lobby = lobby;
    }

    @Override
    public void run() {
        init();
        createStaticState();
        LinkedList<State> nextStates;
        LinkedList<State> newStates;
        while (!endGame) {
            try {
                Thread.sleep(100); //time per frame (10 fps)
                //readPlayers();
                readActions();
                //se realizan las comunicaciones a traves de eventos y 
                //se generan nuevos estados que seran computados
                newStates = new LinkedList<>();
                for (State state : states) {
                    LinkedList<State> newState = state.generate(states, staticStates, actions);
                    if (newState != null) {
                        newStates.addAll(newState);
                    }
                }
                states.addAll(newStates);
                //se generan los estados siguientes incluyendo los generados
                nextStates = new LinkedList<>();
                for (State state : states) {
                    nextStates.add(state.next(states, staticStates, actions));
                }
                //se crean los nuevos estados con los calculados anteriormente
                for (int i = 0; i < states.size(); i++) {
                    states.get(i).createState(nextStates.get(i));
                    states.get(i).clearEvents();
                }
                createState();
                //recorre los player que entran o salen del juego para agregarlos
                //o quitarlos de la lista de gameViews
                readPlayers();
                //despierta a los hilos para que generen el JSON con el estado
                //correspondiente a la visibilidad de cada jugador
                viewsBarrier.arriveAndAwaitAdvance();
                //barrera hasta que todos los hilos terminan de computar el estado
                viewsBarrier.arriveAndAwaitAdvance();
                lobby.stateReady();
                int i = 0;
                while (i < states.size()) {
                    if (states.get(i).isDestroy()) {
                        //System.out.println("State " + states.get(i).getName() + " is removed.");
                        states.remove(i);
                    } else {
                        i++;
                    }
                }
                //System.out.println("STATIC: " + gameStaticState);
                //System.out.println("DYNAMIC: " + gameFullState);
            } catch (InterruptedException ex) {
                Logger.getLogger(Game.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    public void init() {
        //TODO crear estados dinamicos y estaticos
        states.add(new World(new LinkedList(), "World", false, null,width,height));
        createSpawns();
    }

    public void createSpawns() {//agrega todos los sprites que no sean jugadores
        Random r = new Random();
        //int width = 1366; // Esto estaria bueno tenerlo en la clase World y despues poder referenciarlo
        //int height = 639;
        int x, y,v;
        int cantAsteroides=50;//5

        for (int i = 0; i < cantAsteroides; i++) {
            x = r.nextInt(width); //250;
            y = r.nextInt(height); //180 + 10*i; 
            v= r.nextInt(5)+10;
            states.add(new Asteroide("Asteroide", false, ""+i, x, y, v, 0,width,height,"ROJO"));
        }
        
        
        /*
        for (int i = 0; i < 10; i++) {
            x = r.nextInt(width);
            y = r.nextInt(height);
            states.add(new Moneda("Moneda", false,""+i, x, y, 0, 0, width, height));
        }
        /*
         * (String name, boolean destroy, String id, double x, double y, double velocidadX, 
            double velocidadY, double xDir, double yDir, int cantProj, NavePlayer prop,
             String posible, boolean d, String p)
         
        for (int i = 0; i < 5; i++) {
            //System.out.println("cargando naves neutras");
            x = r.nextInt(width);
            y = r.nextInt(height-50);
            states.add(new NaveNeutra("NaveNeutra",false, "neutra"+i, x, y, 0, 0,1,0, 0,true, "",0,width,height));
        }
        */

    }

    private void createStaticState() {
        JSONObject jsonStaticStates = new JSONObject();
        int i = 0;
        for (StaticState staticState : staticStates) {
            jsonStaticStates.put(i + "", staticState.toJSON());
            i++;
        }
        gameStaticState = jsonStaticStates.toString();
    }

    private void createState() {
        JSONObject jsonFullStates = new JSONObject();
        JSONObject jsonStates = new JSONObject();
        int i = 0;
        int j = 0;
        for (State state : states) {
            jsonFullStates.put(i + "", state.toJSON());
            if (state.hasChanged()) {
                jsonStates.put(j + "", state.toJSON());
                j++;
            }
            i++;
        }
        gameFullState = jsonFullStates.toString();
        gameState = jsonStates.toString();
    }
    
    public /*State*/String get_jugador(String id) {
        //State jugador=null;
        JSONObject jugador= new JSONObject();
        int i = 0;
        int cant = states.size();
        boolean continuar = true;
        State state;
        while (i<cant && continuar) {
            state = states.get(i);
            if (state.id==id) {
                jugador.put(0 + "", state.toJSON());//jugador = state;
                continuar= false;
            }
            i++;
        }
        return jugador.toString();
    }

    public void readActions() {
        actions.clear();
        for (Map.Entry<String, HashMap<String, JSONObject>> actionsSend : actionsSended.entrySet()) {
            String sessionId = actionsSend.getKey();
            HashMap<String, JSONObject> newActions = actionsSend.getValue();
            LinkedList<Action> newActionsList = new LinkedList<>();
            //esta lista es solo con proposito de testeo. Para imprimir los nombres de las acciones realizadas
            LinkedList<String> newActionsNameList = new LinkedList<>();
            for (Map.Entry<String, JSONObject> newAction : newActions.entrySet()) {
                String newActionName = newAction.getKey();
                JSONObject newActionJSON = newAction.getValue();
                Action newActionObject = null;
                try {
                    newActionObject = new Action(sessionId, newActionName);

                    JSONArray jsonParameters = (JSONArray) newActionJSON.get("parameters");
                    if (jsonParameters != null) {
                        for (int i = 0; i < jsonParameters.size(); i++) {
                            JSONObject parameter = (JSONObject) jsonParameters.get(i);
                            newActionObject.putParameter((String) parameter.get("name"), (String) parameter.get("value"));
                        }
                    }
                } catch (Exception ex) {
                    newActionObject = new Action(sessionId, newActionName);
                } finally {
                    newActionsList.add(newActionObject);
                    newActionsNameList.add(newActionName);
                }
            }
            //System.out.println("Player " + sessionId + " do actions: " + newActionsNameList.toString());
            actions.put(sessionId, newActionsList);
            actionsSended.remove(sessionId);
        }
    }

    public void addAction(String sessionId, String action) {
        JSONParser parser = new JSONParser();
        JSONObject newAction;
        try {
            newAction = (JSONObject) parser.parse(action);
        } catch (ParseException ex) {
            newAction = new JSONObject();
            newAction.put("name", action);
        }
        String newActionName = newAction.get("name") != null ? (String) newAction.get("name") : null;
        int newPriority = newAction.get("priority") != null ? Integer.parseInt((String) newAction.get("priority")) : 0;
        if (newActionName != null) {
            if (actionsSended.containsKey(sessionId)) {
                JSONObject actualAction = actionsSended.get(sessionId).get(newActionName);
                if (actualAction != null) {
                    int actualPriority = actualAction.get("priority") != null ? Integer.parseInt((String) actualAction.get("priority")) : 0;
                    if (newPriority > actualPriority) {
                        actionsSended.get(sessionId).put(newActionName, newAction);
                    }
                } else {
                    actionsSended.get(sessionId).put(newActionName, newAction);
                }
            } else {
                HashMap<String, JSONObject> newActions = new HashMap<>();
                newActions.put(newActionName, newAction);
                actionsSended.put(sessionId, newActions);
            }
        }

    }

    public void readPlayers() {
        if (gameViewsSended.size() > 0) {
            for (Map.Entry<String, String> gameViewSended : gameViewsSended.entrySet()) {
                String sessionId = gameViewSended.getKey();
                String action = gameViewSended.getValue();
                if (action == "enter") {
                    //aumento en uno los miembros de la barrera
                    //(tal ves hay que hacerlo en el hilo del gameView)
                    viewsBarrier.register();
                    //creo el nuevo hilo
                    GameView gameView = new GameView(sessionId, states, staticStates, actions, viewsBarrier);
                    Thread threadGameView = new Thread(gameView);
                    threadGameView.start();
                    //lo agrego a la lista de gridViews
                    gameViews.put(sessionId, gameView);
                } else if (action == "leave") {
                    //disminuyo en uno los miembros de la barrera
                    //(tal ves hay que hacerlo en el hilo del gameView)
                    //viewsBarrier.arriveAndDeregister();
                    //mato el hilo seteando su variable de terminancion y realizando un notify
                    //try{
                    gameViews.get(sessionId).stop();
                    //catch(Exception e){System.out.println(e.getMessage());}
                    //lo elimino de la lista de gridViews
                    gameViews.remove(sessionId);
                }
            }
            gameViewsSended.clear();
        }
    }

    public void addPlayer(String sessionId) {
        gameViewsSended.put(sessionId, "enter");
    }

    public void removePlayer(String sessionId) {
        gameViewsSended.put(sessionId, "leave");
    }

    public boolean isEndGame() {
        return endGame;
    }

    public void endGame() {
        endGame = true;
    }

    public String getGameState() {
        return gameState;
    }

    public String getGameState(String sessionId) {
        return gameViews.get(sessionId) != null ? gameViews.get(sessionId).getGameState() : "{}";
    }

    public String getGameFullState() {
        return gameFullState;
    }

    public String getGameStaticState() {
        return gameStaticState;
    }
    
    public String getCuanto_color(String color, String id_jugador)//corregir
    {
        String cuanto="";
        NavePlayer j;
        
        int i = 0;
        int cant = states.size();
        double pos_x_jugador;
        double pos_y_jugador;
        double angulo_jugador;
        
        State state;
        double porcentaje_cuanto_color=0;
        //int cant_asteroides=0;
        
        //if (i<cant) {
        if (cant>0) {
            state = states.get(i);
            j = state.getPlayer(id_jugador, states);            
            pos_x_jugador = j.getX();
            pos_y_jugador = j.getY();
            angulo_jugador = angulo_al_origen(pos_x_jugador, pos_y_jugador);
            Vector2 direccion_jugador = j.getDireccion();
            double angulo_direccion = angulo_al_origen(direccion_jugador.x, direccion_jugador.y);//angulo_al_punto(x_direccion, y_direccion, angulo_jugador);
            //concentracion += " - angulo a la direccion: " + angulo_direccion;
            
            LinkedList<Asteroide> asteroides = state.getAsteroides(states);
            asteroides = detectar_objetos(asteroides, pos_x_jugador, pos_y_jugador, angulo_jugador, angulo_direccion /*direccion_jugador*/);
            
            //cuanto = "Jugador " + j.id + ": (x,y)=(" + pos_x_jugador + "," + pos_y_jugador + "), angulo: " + angulo_jugador;
            
            cant = asteroides.size();
            //cuanto = "Asteroides: " + cant + ", ";
            double pos_x_asteroide = 0;
            double pos_y_asteroide = 0;
            boolean continuar=true;
            
            while(i<cant && continuar)
            {
                Asteroide a = asteroides.get(i);
                //cuanto += a.name + " " + a.get_color();
                if(a.get_color().equalsIgnoreCase(color))
                {                   
                    pos_x_asteroide = a.getX();
                    pos_y_asteroide = a.getY();
                    double distancia_asteroide = distancia(pos_x_jugador,pos_y_jugador,pos_x_asteroide,pos_y_asteroide);
                    
                    /*
                    double angulo_asteroide = angulo_al_punto(pos_x_asteroide, pos_y_asteroide, angulo_jugador);                  
                    
                    if(dentro_del_area(angulo_asteroide,distancia_asteroide))
                    {*/
                        //cant_asteroides++;
                        double porcentaje_asteroide = porcentaje_deteccion(distancia_asteroide);
                        
                        /*cuanto += "Asteroide" + i + ": (x,y)=(" + pos_x_asteroide + "," + pos_y_asteroide + "), angulo=" + angulo_asteroide + 
                            ", distancia=" + distancia_asteroide + ", pocentaje=" + porcentaje_asteroide +  " - ";
                        */
                        if(porcentaje_asteroide>porcentaje_cuanto_color)
                        {
                            porcentaje_cuanto_color = porcentaje_asteroide;
                        }
                        
                        if(porcentaje_cuanto_color>=porcentaje)
                        {
                            continuar=false;
                        }
                    //}                                        
                }                
                i++;
            }            
        }
        
        cuanto += /*"Dentro del área: " + cant_asteroides + ", Porcentaje: "*/ porcentaje_cuanto_color;
        
        return cuanto;
    }
    
    private double distancia(double x1, double y1, double x2, double y2) //ok
    {//calcula la distancia entre dos puntos: (x1,y1) (x2,y2), usando el teorema de Pitágoras
        return Math.sqrt(Math.pow(x2-x1, 2) + Math.pow(y2-y1, 2));
    }
    
    private double angulo_al_origen(double x, double y)
    {//angulo que forma un punto (x,y) respecto al origen (0,0) como el arcoseno del cateto opuesto al ángulo sobre la raíz cuadrada de la suma de los
        //cuadrados de los catetos
        double angulo=0;
        
        angulo = Math.asin(y/Math.sqrt(Math.pow(y, 2) + Math.pow(x,2)));
        
        return angulo;
    }
    /*
    private double angulo_al_punto(double x, double y, double angulo_referencia)//original
    {//angulo que forma el punto (x,y) respecto al jugador, teniendo en cuenta el ángulo que forma la posición de ése jugador respecto al origen
        double angulo=0;
                
        if(x>=0)
        {
            angulo = Math.asin(y/Math.sqrt(Math.pow(x, 2)+Math.pow(y,2)));
            
            if(angulo<0)
            {
                angulo=2*Math.PI + angulo;
            }
        }
        else
        {
            angulo = Math.PI - Math.asin(y/Math.sqrt(Math.pow(x, 2)+Math.pow(y,2)));            
        }
        
        angulo = angulo - (angulo_referencia+ Math.PI/2);//por qué - 90°?
        
        if(angulo<0)
        {
            angulo=2*Math.PI+angulo;
        }
        else
        {
            if(angulo>2*Math.PI)
            {
                angulo = angulo % 2 * Math.PI;
            }
        }
        
        return angulo;//Math.round(angulo);
    }
    */
    // 31/3 18:30hs
    //explicar estas fórmulas y demás que resuelven cuanto_color y buscar_color
    //con fórmulas y graficos matemáticos explicativos
    //según eso modificar
    private double angulo_al_punto(double x, double y, double angulo_referencia)
    {//angulo que forma el punto (x,y) respecto al jugador, teniendo en cuenta el ángulo que forma la posición de ése jugador respecto al origen        
        double angulo = Math.asin(y/Math.sqrt(Math.pow(x, 2)+Math.pow(y,2))); //ángulo que forma un punto de los ejes cartesianos respecto al origen como el arcseno
        
        if(x>=0)
        {            
            if(angulo<0)
            {
                angulo=2*Math.PI + angulo;
            }
        }
        else
        {
            if(angulo>0)
            {
                angulo=0.5*Math.PI + angulo;
            }
            else
                angulo = 1.5 * Math.PI + angulo;                     
        }
                
        angulo = angulo - angulo_referencia;
        
        if(angulo<0)
        {
            angulo=2*Math.PI+angulo;
        }
        else
        {
            if(angulo>2*Math.PI)
            {
                angulo = angulo % 2 * Math.PI;
            }
        }
                
        return angulo;
    }
    
    private boolean dentro_del_area(double angulo_obj, double distancia_obj, double angulo_direccion)//REVISAR!!!!!
    {//tener en cuenta la dirección del jugador
        //double angulo_jugador=0;//pues las posiciones fueron recalculadas respecto al jugador
        double angulo_min = angulo_0_a_360(angulo_direccion - angulo_cono); //angulo_0_a_360(2*Math.PI +(angulo_jugador - angulo_cono));
        double angulo_max = angulo_0_a_360(angulo_direccion + angulo_cono); //angulo_0_a_360(angulo_jugador + angulo_cono);
        
        //concentracion += " - angulo_min:" + angulo_min + " angulo_max:" + angulo_max;
        
        if((angulo_obj>=angulo_min && angulo_obj < angulo_max) && distancia_obj<distancia_max)  
            return true;        
        else
            return false;
    }
    
    private double angulo_0_a_360(double angulo) //ok
    {
        if(angulo<0)
        {
            angulo = 2*Math.PI + angulo;
        }
        else
        {
            if(angulo>2*Math.PI)
            {
                angulo = angulo % (2*Math.PI);
            }
        }
        
        return angulo;
    }
    
    private double porcentaje_deteccion(double distancia_a_objeto)
    {//determinar el valor del coeficiente
        double res;
        if(distancia_a_objeto<=0) //DEBE SER 0??
        {
            res=100; //una vez que el robot se acerque al objeto hacer que no modifique su velocidad!!!
        }
        else
        {
            //verificar valor del coeficiente!!!
            res = coeficiente/distancia_a_objeto; //porcentaje - (porcentaje * distancia_a_objeto / distancia_max);
        }         
        return res;//Math.round(res);
    }
    
    public String getBuscar_color(String color, String id_jugador)
    {//siempre devuelve el mismo valor sin tener en cuenta la visión del jugador
        String concentracion="";
        //LinkedList<Asteroide> asteroides = new LinkedList();
        NavePlayer j;
        
        int i = 0;
        int cant = states.size();
        double pos_x_jugador;
        double pos_y_jugador;
        double angulo_jugador;
        
        State state;
        double concentracion_buscar_color=0;
        //int cant_asteroides=0;
        
        if (i<cant) {
            //concentracion = "revisa mundo ";
            state = states.get(i);
            j = state.getPlayer(id_jugador, states);            
            pos_x_jugador = j.getX();
            pos_y_jugador = j.getY();
            angulo_jugador = angulo_al_origen(pos_x_jugador, pos_y_jugador);//ángulo que forma el jugador respecto al origen de coordenadas del juego
            Vector2 direccion_jugador = j.getDireccion();
            double angulo_direccion = angulo_al_origen(direccion_jugador.x, direccion_jugador.y);//angulo_al_punto(x_direccion, y_direccion, angulo_jugador);
            //concentracion += " - angulo a la direccion: " + angulo_direccion;
            //concentracion = " Dirección original jugador: ("+ direccion_jugador.x + "," + direccion_jugador.y + ")";//"Jugador " + j.id + ": (x,y)=(" + pos_x_jugador + "," + pos_y_jugador + "), angulo: " + angulo_jugador;
                        
            LinkedList<Asteroide> asteroides = state.getAsteroides(states);
            //asteroides = convertir(asteroides, pos_x_jugador, pos_y_jugador, angulo_jugador);
            asteroides = detectar_objetos(asteroides, pos_x_jugador, pos_y_jugador, angulo_jugador, angulo_direccion /*direccion_jugador*/);
            
            cant = asteroides.size();
            //concentracion = "Asteroides: " + cant + ", ";
            //double pos_x_asteroide = 0;
            //double pos_y_asteroide = 0;
            boolean continuar=true;
            
            while(i<cant && continuar)
            {
                Asteroide a = asteroides.get(i);
                //concentracion += a.name + " " + a.get_color();
                if(a.get_color().equalsIgnoreCase(color))
                {                 
                    double angulo_asteroide = a.getAngulo();
                    /*concentracion += " angulo:" + angulo_asteroide;
                    
                    pos_x_asteroide = a.getX();
                    pos_y_asteroide = a.getY();                    
                    
                    //double angulo_asteroide = angulo_al_punto(pos_x_asteroide, pos_y_asteroide, angulo_jugador);
                    
                    double distancia_asteroide = distancia(pos_x_jugador,pos_y_jugador,pos_x_asteroide,pos_y_asteroide);
                    
                    if(dentro_del_area(angulo_asteroide,distancia_asteroide))
                    {*/
                        double concentracion_asteroide = concentracion_color(angulo_asteroide, angulo_direccion);
                        
                        if(concentracion_asteroide>concentracion_buscar_color)
                        {
                            concentracion_buscar_color = concentracion_asteroide;
                            //continuar=false;//puede que se detenga al primer detectado
                        }
                        
                        if(concentracion_buscar_color>=concentracion_max)
                        {
                            continuar=false;
                        }
                    //}                                        
                }                
                i++;
            }            
        }
        
        concentracion += " "+/*"Dentro del área: " + cant_asteroides + ", Porcentaje: "*/ concentracion_buscar_color;
        
        return concentracion;
    }
    
    private LinkedList<Asteroide> detectar_objetos(LinkedList<Asteroide> asteroides, double pos_x_jugador, double pos_y_jugador, double angulo_jugador,
                                                   /*Vector2 direccion_jugador*/ double angulo_direccion)
    {
        //se recalculan las posiciones de los objetos respecto a la posición del robot
        LinkedList<Asteroide> objetos = convertir(asteroides, pos_x_jugador, pos_y_jugador, angulo_jugador);            
        
        objetos = objetos_dentro_area(objetos, angulo_direccion);
        
        return objetos;
    }
    
   
    
    private LinkedList<Asteroide> objetos_dentro_area(LinkedList<Asteroide> objetos, double angulo_direccion)
    {       
        LinkedList<Asteroide> detectados = new LinkedList<Asteroide>();
        int cant = objetos.size();
        
        for(int i = 0; i<cant; i++)
        {
            Asteroide a = objetos.get(i);
            if(dentro_del_cono(a, angulo_direccion))
            {
                detectados.add(a);
            }
        }        
        
        return detectados;
    }
    
    private boolean dentro_del_cono(Asteroide a, double angulo_direccion)
    {
        boolean dentro=false;
        
        double angulo_asteroide = a.getAngulo();
        //el jugador está en la posición (0,0) después de convertir las posiciones de los objetos respecto a él
        double pos_x_jugador = 0;
        double pos_y_jugador = 0;
        double pos_x_asteroide = a.getX();
        double pos_y_asteroide = a.getY();
        double distancia_al_asteroide = distancia(pos_x_jugador,pos_y_jugador,pos_x_asteroide,pos_y_asteroide);
        dentro = dentro_del_area(angulo_asteroide,distancia_al_asteroide, angulo_direccion);
        
        return dentro;
    }
    
    private LinkedList<Asteroide> convertir(LinkedList<Asteroide> asteroides, double pos_x_jugador, double pos_y_jugador, double angulo_jugador)
    {
        LinkedList<Asteroide> objetos = new LinkedList<Asteroide>();
        
        int cant = asteroides.size();
        //concentracion += "asteroides revisados:" + cant;
        
        for(int i = 0; i<cant; i++)
        {            
            Asteroide a = asteroides.get(i);
            double x = nuevo_x(a.getX(), pos_x_jugador);           
            double y = nuevo_y(a.getY(), pos_y_jugador);
            Vector2 v = a.getVelocidad();
            double vx = v.x;
            double vy = v.y;
            Asteroide ac = new Asteroide(a.name, a.destroy, a.id, x, y, vx, vy, a.getWorldWidth(), a.getWorldHeight(), a.get_color());             
            //se obtiene el ángulo que forma el punto de la posición del objeto, recalculada, respecto al nuevo origen que es la pocisión del robot 
            double angulo_asteroide = angulo_al_origen(x,y); //angulo_al_punto(x, y, angulo_jugador);
            ac.setAngulo(angulo_asteroide); 
            
            //concentracion += ", asteroide convertido x=" + x + " y=" + y + " angulo=" + angulo_asteroide;  
            
            objetos.add(ac);
        }
        
        return objetos;
    }
    
    private double nuevo_x(double x_a_convertir, double x_nuevo_origen) //ok
    {
        double x = x_a_convertir - x_nuevo_origen;
        
        return x;
    }
    
    private double nuevo_y(double y_a_convertir, double y_nuevo_origen) //ok
    {
        double y = y_a_convertir - y_nuevo_origen;
        
        return y;
    }
    
    private double concentracion_color(double un_angulo, double angulo_direccion) //MODIFICAR CONSIDERANDO EL ANGULO DE LA DIRECCION DEL ROBOT
    {//SI EL ANGULO RECIBIDO POR PARAMETRO COINCIDE CON ESE ANGULO DIRECCION ENTONCES DEVOLVER 80
        //EN CAMBIO SI ES MENOR ENTONCES DEVOLVER UN VALOR ENTRE 0.0001 Y 79.9999
        //SI ES MAYOR A ESTE ANGULO ENTONCES DEVOLVER UN VALOR ENTRE 80.001 Y 160 S
        
        //YA QUE CONSIDERO QUE LOS EJES CARTESIANOS NO GIRAN DE ACUERDO AL VECTOR DE LA DIRECCION DEL ROBOT
        //SINO QUE PERMANECEN FIJOS SEGÚN LO DETERMINA PHASER
        //Y LO UNICO QUE CAMBIA ES EL ORIGEN
        double c = 80;
        
        if(un_angulo==angulo_direccion)
        {
            return c;
        }
        else
        {
            if(un_angulo<angulo_direccion)
            {
                c = un_angulo % 80;
                
                return c;
            }
            else
            {
                c=un_angulo % 81+80;
                return c;
            }                
        }
            
        /*
        if(un_angulo<=2*Math.PI && un_angulo>=(2*Math.PI-angulo_cono))
        {
            return Math.round(concentracion_max-((-concentracion_frontal * (2*Math.PI-un_angulo)/ angulo_cono) + concentracion_frontal));
                    //Math.round(concentracion_max - (concentracion_frontal+(-concentracion_frontal * (2*Math.PI-un_angulo) / angulo_cono)));
        }
        else
        {
            return Math.round((- concentracion_frontal * un_angulo / angulo_cono) + concentracion_frontal);
        }
*/
    }
    
}
