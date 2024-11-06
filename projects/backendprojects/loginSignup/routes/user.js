const express = require("express");
const User = require("../models/user");
const { createHmac, randomBytes } = require("crypto");

const router = express.Router();

router.get("/signin", (req, res) => {
    return res.render("signin");
});

router.get("/signup", (req, res) => {
    return res.render("signup");
});

router.post("/signup", async (req, res) => {
    const {fullName, email, password} = req.body;

    await User.create({
        fullName,
        email, 
        password,
    });
    return res.redirect("/")
    
});

router.post("/signin", async (req, res) => {
    const {email, password} = req.body;
    const entry = await User.findOne({ email });

    if(!entry) return res.status(404).json({ error: "entry not found!" });

    const salt = entry.salt;
    const hashedPassword = createHmac("sha256", salt)
    .update(password)
    .digest("hex");

    if(hashedPassword != entry.password) return res.status(404).json({ error: "Wrong Password" });

    return res.redirect("/")
    
});

module.exports = router;

