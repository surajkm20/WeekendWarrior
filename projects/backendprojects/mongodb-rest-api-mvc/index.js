const express = require("express");
const fs = require("fs");

const { connectMonogoDB } = require('./connection');
const {logReqRes} = require("./middleware");
const { timeStamp, error } = require("console");

const userRouter = require('./routes/user');

const app = express();
const PORT = 8000;

// connection Model
connectMonogoDB("mongodb://127.0.0.1:27017/mogoDB-Project1").then(()=>console.log("Mongodb connected"));

// Middeleware - Plugin
app.use(express.urlencoded({ extended: false }));
app.use(logReqRes("log.txt"));

// Routes
app.use('/api/users', userRouter);

app.listen(PORT, ()=> {
    console.log(`Server started at PORT: ${PORT}`)
});
