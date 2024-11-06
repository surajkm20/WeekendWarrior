const express = require("express");
const path = require("path");
const userRoute = require("./routes/user");
const mongoose = require("mongoose");

const app = express();
const PORT = 8000;

// const { connectMonogoDB } = require('../connection.js');
// connection Model
// connectMonogoDB("mongodb://127.0.0.1:27017/loginSignup").then(()=>console.log("Mongodb connected"));

// connection Model
mongoose.connect("mongodb://localhost:27017/blogify").then(()=>console.log("Mongodb connected"));

app.set("view engine", "ejs")
app.set("views", path.resolve("./views"));
app.use(express.urlencoded({ extended: false}));

app.get("/", (req, res) => {
    res.render("home");
});


app.use(express.json());
app.use("/user", userRoute);

app.listen(PORT, ()=> console.log(`Server started at ${PORT}`));
