const express = require("express");
let users = require("./MOCK_DATA.json");
const fs = require("fs");

const app = express();
const PORT = 8000;


// Middeleware - Plugin
app.use(express.urlencoded({ extended: false }));

// Routes
app.get("/users", (req, res) =>{
    const html = `
        <ul>
            ${users.map((us)=>`<li>${
                us.first_name}
                </li>`).join("")
            }
        </ul>
    `;
    res.send(html);
    //return res.json(users);
});

// REST API
// GET
// for complete user list
app.get("/api/users", (req, res)=>{
    return res.json(users);
});

// for individual user list
app.route("/api/users/:id").get((req, res)=>{
    const id = Number(req.params.id);
    const user = users.find((user) => user.id === id);
    return res.json(user);
})
.patch((req, res)=>{
    const id = Number(req.params.id);
    users = users.filter(user => user.id !== id);
    
    const body = req.body;
    users.push({...body, id: id});
    fs.writeFile("./MOCK_DATA.json", JSON.stringify(users), (err, data) =>{
        return res.json({status:"Done"});
    });

})
.delete((req, res)=>{
    const id = Number(req.params.id);
    users = users.filter(user => user.id !== id);
    
    fs.writeFile("./MOCK_DATA.json", JSON.stringify(users), (err, data) =>{
        console.log(`${id}`);
        return res.json(users);
    });
    
});

// POST
app.post("/api/users", (req, res)=>{
    const body = req.body;
    users.push({...body, id: users.length+1});
    fs.writeFile("./MOCK_DATA.json", JSON.stringify(users), (err, data) =>{
        return res.json({status:"Done"});
    });
});

app.listen(PORT, ()=> {
    console.log(`Server started at PORT: ${PORT}`)
});
