const express = require("express");
const fs = require("fs");
const urlRoute = require("./routes/url");

const { connectMonogoDB } = require('./connection');
//const {logReqRes} = require("./middleware");

const app = express();
const PORT = 8001;

// connection Model
connectMonogoDB("mongodb://127.0.0.1:27017/custom_url_short").then(()=>console.log("Mongodb connected"));

app.use(express.json());
app.use("/url", urlRoute);

app.listen(PORT, ()=> console.log(`Server started at ${PORT}`));
